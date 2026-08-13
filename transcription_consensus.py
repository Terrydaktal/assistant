from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field

from rapidfuzz.distance import Levenshtein

TOKEN_RE = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z0-9]+)*|[^\w\s]", re.UNICODE)
PUNCTUATION = {".", ",", "?", "!", ";", ":"}


@dataclass
class Word:
    surface: str
    normalized: str
    punctuation: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class AdjudicationRegion:
    start: float
    end: float
    anchor_start: int
    anchor_end: int


def parse_transcript(text: str) -> list[Word]:
    words: list[Word] = []
    for token in TOKEN_RE.findall(text):
        if token in PUNCTUATION:
            if words:
                words[-1].punctuation.append(token)
            continue
        if not re.search(r"\w", token):
            continue
        words.append(
            Word(
                surface=token,
                normalized=token.casefold().replace("’", "'"),
            )
        )
    return words


def align_to_anchor(
    anchor: list[Word],
    candidate: list[Word],
) -> tuple[list[Word | None], list[list[Word]]]:
    anchor_norm = [word.normalized for word in anchor]
    candidate_norm = [word.normalized for word in candidate]
    aligned: list[Word | None] = [None] * len(anchor)
    insertions: list[list[Word]] = [[] for _ in range(len(anchor) + 1)]
    for opcode in Levenshtein.opcodes(anchor_norm, candidate_norm):
        anchor_count = opcode.src_end - opcode.src_start
        candidate_count = opcode.dest_end - opcode.dest_start
        if opcode.tag in {"equal", "replace"}:
            paired = min(anchor_count, candidate_count)
            for offset in range(paired):
                aligned[opcode.src_start + offset] = candidate[opcode.dest_start + offset]
            if candidate_count > paired:
                insertions[opcode.src_start + paired].extend(
                    candidate[opcode.dest_start + paired : opcode.dest_end]
                )
        elif opcode.tag == "insert":
            insertions[opcode.src_start].extend(
                candidate[opcode.dest_start : opcode.dest_end]
            )
    return aligned, insertions


def select_word(anchor_word: Word, votes: list[Word | None]) -> Word | None:
    counts = Counter(word.normalized if word is not None else None for word in votes)
    best_count = max(counts.values())
    winners = {word for word, count in counts.items() if count == best_count}
    if anchor_word.normalized in winners:
        winner = anchor_word.normalized
    elif None in winners:
        winner = None
    else:
        winner = next(
            word.normalized
            for word in votes
            if word is not None and word.normalized in winners
        )
    if winner is None:
        return None
    return next(
        word for word in votes if word is not None and word.normalized == winner
    )


def select_punctuation(votes: list[Word | None], anchor_word: Word) -> list[str]:
    selected: list[str] = []
    for punctuation in (",", ";", ":", ".", "?", "!"):
        positive = sum(
            word is not None and punctuation in word.punctuation for word in votes
        )
        negative = len(votes) - positive
        if positive > negative or (
            positive == negative and punctuation in anchor_word.punctuation
        ):
            selected.append(punctuation)
    return selected


def select_insertion(votes: list[list[Word]]) -> list[Word]:
    keys = [tuple(word.normalized for word in sequence) for sequence in votes]
    counts = Counter(keys)
    best_count = max(counts.values())
    winners = {key for key, count in counts.items() if count == best_count}
    if () in winners:
        winner = ()
    else:
        winner = next(key for key in keys if key in winners)
    return next(
        sequence
        for sequence, key in zip(votes, keys, strict=True)
        if key == winner
    )


def render(words: list[Word]) -> str:
    return " ".join(
        word.surface + "".join(word.punctuation) for word in words
    ).strip()


def _timed_anchor_words(
    anchor: list[Word],
    segments: list[dict[str, object]],
) -> list[tuple[float, float]]:
    timed_words: list[tuple[float, float]] = []
    segment_words: list[Word] = []
    for segment in segments:
        words = parse_transcript(str(segment.get("text", "")))
        segment_words.extend(words)
        if not words:
            continue
        start = float(segment["start"])
        end = max(start, float(segment["end"]))
        step = (end - start) / len(words)
        timed_words.extend(
            (start + index * step, start + (index + 1) * step)
            for index in range(len(words))
        )

    if [word.normalized for word in segment_words] != [
        word.normalized for word in anchor
    ]:
        raise ValueError("Whisper segment words do not match the anchor transcript.")
    return timed_words


def find_disagreement_regions(
    anchor_text: str,
    candidate_text: str,
    anchor_segments: list[dict[str, object]],
    *,
    context_seconds: float = 1.0,
) -> list[AdjudicationRegion]:
    """Locate time ranges where a third recognizer can change a two-way vote."""
    if context_seconds < 0:
        raise ValueError("context_seconds must be non-negative.")

    anchor = parse_transcript(anchor_text)
    if not anchor:
        return []
    candidate = parse_transcript(candidate_text)
    aligned, insertions = align_to_anchor(anchor, candidate)
    timed_words = _timed_anchor_words(anchor, anchor_segments)

    disagreement_indices = {
        index
        for index, (anchor_word, candidate_word) in enumerate(zip(anchor, aligned, strict=True))
        if candidate_word is None
        or anchor_word.normalized != candidate_word.normalized
        or set(anchor_word.punctuation) != set(candidate_word.punctuation)
    }
    for gap_index, insertion in enumerate(insertions):
        if insertion:
            disagreement_indices.add(min(len(anchor) - 1, max(0, gap_index)))

    raw_ranges = [
        (
            max(0.0, timed_words[index][0] - context_seconds),
            timed_words[index][1] + context_seconds,
        )
        for index in sorted(disagreement_indices)
    ]
    if not raw_ranges:
        return []

    merged_ranges: list[list[float]] = []
    for start, end in raw_ranges:
        if merged_ranges and start <= merged_ranges[-1][1]:
            merged_ranges[-1][1] = max(merged_ranges[-1][1], end)
        else:
            merged_ranges.append([start, end])

    regions: list[AdjudicationRegion] = []
    for start, end in merged_ranges:
        covered = [
            index
            for index, (word_start, word_end) in enumerate(timed_words)
            if word_end > start and word_start < end
        ]
        if not covered:
            continue
        region = AdjudicationRegion(
            start=start,
            end=end,
            anchor_start=covered[0],
            anchor_end=covered[-1] + 1,
        )
        if regions and region.anchor_start < regions[-1].anchor_end:
            previous = regions[-1]
            regions[-1] = AdjudicationRegion(
                start=previous.start,
                end=region.end,
                anchor_start=previous.anchor_start,
                anchor_end=max(previous.anchor_end, region.anchor_end),
            )
        else:
            regions.append(region)
    return regions


def merge_adaptive_transcripts(
    anchor_text: str,
    candidate_text: str,
    regions: list[AdjudicationRegion],
    adjudications: list[str],
) -> str:
    """Use a third vote inside selected regions and preserve the anchor elsewhere."""
    if len(regions) != len(adjudications):
        raise ValueError("Every adjudication region requires one transcript.")

    anchor = parse_transcript(anchor_text)
    candidate = parse_transcript(candidate_text)
    candidate_alignment = align_to_anchor(anchor, candidate)
    adjudicator_aligned: list[Word | None] = [*anchor]
    adjudicator_insertions: list[list[Word]] = [
        [] for _ in range(len(anchor) + 1)
    ]

    previous_end = 0
    for region, adjudication in zip(regions, adjudications, strict=True):
        if not 0 <= region.anchor_start < region.anchor_end <= len(anchor):
            raise ValueError(f"Invalid adjudication anchor range: {region!r}")
        if region.anchor_start < previous_end:
            raise ValueError("Adjudication regions must not overlap.")
        previous_end = region.anchor_end

        local_anchor = anchor[region.anchor_start : region.anchor_end]
        local_aligned, local_insertions = align_to_anchor(
            local_anchor,
            parse_transcript(adjudication),
        )
        adjudicator_aligned[region.anchor_start : region.anchor_end] = local_aligned
        for local_gap, insertion in enumerate(local_insertions):
            adjudicator_insertions[region.anchor_start + local_gap] = insertion

    alignments = [
        ([*anchor], [[] for _ in range(len(anchor) + 1)]),
        candidate_alignment,
        (adjudicator_aligned, adjudicator_insertions),
    ]
    return _render_alignments(anchor, alignments)


def _render_alignments(
    anchor: list[Word],
    alignments: list[tuple[list[Word | None], list[list[Word]]]],
) -> str:
    output_words: list[Word] = []
    for gap_index in range(len(anchor) + 1):
        output_words.extend(
            select_insertion(
                [insertions[gap_index] for _, insertions in alignments]
            )
        )
        if gap_index == len(anchor):
            break
        votes = [aligned[gap_index] for aligned, _ in alignments]
        selected = select_word(anchor[gap_index], votes)
        if selected is not None:
            output_words.append(
                Word(
                    surface=selected.surface,
                    normalized=selected.normalized,
                    punctuation=select_punctuation(votes, anchor[gap_index]),
                )
            )
    return render(output_words)


def merge_transcripts(transcripts: list[str]) -> str:
    if len(transcripts) < 3 or len(transcripts) % 2 == 0:
        raise ValueError("Consensus requires an odd number of at least three transcripts.")

    parsed = [parse_transcript(transcript) for transcript in transcripts]
    anchor = parsed[0]
    alignments = [
        ([*candidate], [[] for _ in range(len(anchor) + 1)])
        if index == 0
        else align_to_anchor(anchor, candidate)
        for index, candidate in enumerate(parsed)
    ]

    return _render_alignments(anchor, alignments)
