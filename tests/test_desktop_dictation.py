import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "desktop_dictation"
    / "transcribe-and-type.py"
)
SPEC = importlib.util.spec_from_file_location("transcribe_and_type", MODULE_PATH)
transcribe_and_type = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(transcribe_and_type)


class DeliveryTimingFormatTests(unittest.TestCase):
    @patch.object(transcribe_and_type.time, "time", return_value=2.002)
    def test_total_breakdown_names_non_server_overhead(self, _mock_time):
        details = transcribe_and_type.format_delivery_timing(
            {
                "client_stop_to_upload_ms": 211,
                "client_stop_finalize_ms": 62,
                "client_queue_wait_ms": 149,
                "client_file_read_ms": 0,
                "server_total_ms": 693,
            },
            stop_requested_ms=1000,
            type_ms=81,
        )

        self.assertEqual(
            details,
            "total_ms=1002 (client_stop_to_upload_ms=211 + "
            "client_network_and_delivery_overhead_ms=17 + server_total_ms=693 + "
            "client_paste_ms=81)",
        )


if __name__ == "__main__":
    unittest.main()
