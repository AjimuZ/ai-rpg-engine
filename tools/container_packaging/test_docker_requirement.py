"""Check the missing-Docker policy shared by the default BuildKit and Compose checks.

Inputs are synthetic environments and executable lookups; no Docker command runs
and the real process environment is not read. Outputs are test assertions.
"""

import unittest

from docker_requirement import REQUIRE_FLAG, docker_required, missing_docker_reason


def absent(_name: str) -> None:
    """Simulate a host with no `docker` on PATH."""
    return None


def present(name: str) -> str:
    """Simulate a host where the executable resolves."""
    return f"/usr/bin/{name}"


class DockerRequirementTest(unittest.TestCase):
    """A missing CLI may skip only when no environment demands Docker."""

    def test_present_cli_always_runs(self) -> None:
        """An installed CLI runs the checks; its own errors still fail them."""
        self.assertIsNone(missing_docker_reason({}, present))
        self.assertIsNone(missing_docker_reason({REQUIRE_FLAG: "1"}, present))

    def test_missing_cli_skips_only_without_requirement(self) -> None:
        """The reason names the escape hatch and that hosted CI still enforces it."""
        reason = missing_docker_reason({}, absent)
        self.assertIsNotNone(reason)
        self.assertIn("docker executable not found", reason)
        self.assertIn("hosted CI enforces", reason)
        self.assertIn(f"{REQUIRE_FLAG}=1", reason)

    def test_required_environments_run_and_fail_on_missing_cli(self) -> None:
        """The CI flag and any hosted GitHub Actions job must never turn into a skip."""
        for environ in ({REQUIRE_FLAG: "1"}, {"GITHUB_ACTIONS": "true"},
                        {REQUIRE_FLAG: "1", "GITHUB_ACTIONS": "true"}):
            with self.subTest(environ=environ):
                self.assertTrue(docker_required(environ))
                self.assertIsNone(missing_docker_reason(environ, absent))

    def test_other_values_do_not_require_docker(self) -> None:
        """Only the exact documented values count, matching the other PACKAGING_* flags."""
        for environ in ({}, {REQUIRE_FLAG: "0"}, {REQUIRE_FLAG: ""}, {REQUIRE_FLAG: "true"},
                        {"GITHUB_ACTIONS": "false"}, {"CI": "true"}):
            with self.subTest(environ=environ):
                self.assertFalse(docker_required(environ))
                self.assertIsNotNone(missing_docker_reason(environ, absent))


if __name__ == "__main__":
    unittest.main()
