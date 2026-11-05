import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from pairing import Pairing


class PairingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1000
        self.pairing = Pairing(Path(self.tmp.name) / "pair.sqlite", clock=lambda: self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def test_bootstrap_and_pair_second_device_once(self):
        phone = self.pairing.redeem_code(self.pairing.bootstrap_code())
        self.assertTrue(self.pairing.authenticated(phone.token))
        self.assertEqual(phone.id, self.pairing.device_id(phone.token))
        with self.assertRaises(PermissionError):
            self.pairing.bootstrap_code()
        code = self.pairing.issue_code(phone.token)
        tablet = self.pairing.redeem_code(code)
        self.assertTrue(self.pairing.authenticated(tablet.token))
        self.assertNotEqual(phone.id, tablet.id)
        self.assertNotEqual(phone.token, tablet.token)
        with self.assertRaises(PermissionError):
            self.pairing.redeem_code(code)

    def test_numeric_code_keeps_leading_zero_and_avoids_active_collision(self):
        with patch("pairing.secrets.randbelow", side_effect=[7, 7, 7, 42]):
            first = self.pairing.bootstrap_code()
            self.assertEqual("00000007", first)
            phone = self.pairing.redeem_code(first)
            code = self.pairing.issue_code(phone.token)
            second = self.pairing.issue_code(phone.token)
            self.assertEqual("00000007", code)
            self.assertEqual("00000042", second)
            self.assertTrue(self.pairing.authenticated(self.pairing.redeem_code(second).token))

    def test_expiry_and_unauthorized_device(self):
        code = self.pairing.bootstrap_code()
        self.now += 300
        with self.assertRaises(PermissionError):
            self.pairing.redeem_code(code)
        with self.assertRaises(PermissionError):
            self.pairing.issue_code("invalid")

    def test_lost_response_can_be_retried_without_reusing_code_for_another_device(self):
        code = self.pairing.bootstrap_code()
        request_id = "retriable_request_123456789"
        first = self.pairing.redeem_code(code, request_id)
        self.assertEqual(first, self.pairing.redeem_code(code, request_id))
        with self.assertRaises(PermissionError):
            self.pairing.redeem_code(code, "another_request_123456789")
        with self.assertRaises(PermissionError):
            self.pairing.redeem_code("wrong", request_id)
        self.now += 300
        with self.assertRaises(PermissionError):
            self.pairing.redeem_code(code, request_id)

    def test_concurrent_redemption_only_succeeds_once(self):
        code = self.pairing.bootstrap_code()
        def redeem(_):
            try:
                self.pairing.redeem_code(code)
                return True
            except PermissionError:
                return False
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(1, sum(pool.map(redeem, range(2))))


if __name__ == "__main__":
    unittest.main()
