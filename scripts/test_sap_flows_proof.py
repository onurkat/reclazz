#!/usr/bin/env python3
"""Portable rejection checks for the SDK flow acceptance predicate."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('sap_flows', Path(__file__).with_name('test-sap-flows-sdk.py'))
flows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flows)


class FlowProofTest(unittest.TestCase):
    def setUp(self):
        self.receipt = dict(requestId='request1', className='com.example.DynamicHandler',
                            expectedSha256='a' * 64, observedSha256='a' * 64, status='applied',
                            sessionId='session1', completedAt='2026-10-04T00:00:00Z')
        self.expected = flows.expected_body('dynamic', 2, 'nonce1', 123, '6.2.12')
        self.body = dict(self.expected)

    def check(self, status=200):
        flows.check_proof(self.receipt, 'request1', 'com.example.DynamicHandler', 'a' * 64,
                          'session1', status, self.body, self.expected)

    def test_exact_receipt_and_behavior_are_accepted(self):
        self.check()

    def test_each_correlation_field_is_required(self):
        for field in ['requestId', 'className', 'expectedSha256', 'observedSha256', 'sessionId']:
            with self.subTest(field=field):
                original = self.receipt[field]
                self.receipt[field] = 'different'
                with self.assertRaises(AssertionError):
                    self.check()
                self.receipt[field] = original

    def test_non_applied_outcomes_cannot_certify_changed_behavior(self):
        for status in ['running', 'unverified', 'failed', 'mismatch', 'not_observed']:
            with self.subTest(status=status):
                self.receipt['status'] = status
                with self.assertRaises(AssertionError):
                    self.check()

    def test_missing_completion_is_not_success(self):
        self.receipt['completedAt'] = ''
        with self.assertRaises(AssertionError):
            self.check()

    def test_matching_receipt_cannot_certify_stale_handler(self):
        self.body['held'] = 'dynamic-v1:nonce1'
        with self.assertRaises(AssertionError):
            self.check()

    def test_each_observable_must_match(self):
        for field in self.expected:
            with self.subTest(field=field):
                self.body = dict(self.expected)
                self.body[field] = 'wrong'
                with self.assertRaises(AssertionError):
                    self.check()

    def test_failed_http_cannot_pass_with_correct_body(self):
        with self.assertRaises(AssertionError):
            self.check(500)

    def test_job_result_status_and_count_are_required(self):
        self.expected = flows.expected_body('job', 2, 'nonce1', 123, '6.2.12')
        self.body = dict(self.expected)
        self.check()
        for field in ['result', 'currentResult', 'status', 'currentStatus', 'jobs', 'performable', 'abortable']:
            with self.subTest(field=field):
                self.body = dict(self.expected)
                self.body[field] = 'wrong'
                with self.assertRaises(AssertionError):
                    self.check()


if __name__ == '__main__':
    unittest.main()
