"""Check the missing-Docker policy shared by the default BuildKit and Compose checks.

Inputs are synthetic environments and executable lookups; no Docker command runs
and the real process environment is not read. Outputs are test assertions.
"""

import unittest

from docker_requirement import DEMAND_FLAG, REQUIRE_FLAG, docker_required, missing_docker_reason, missing_image_reason


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


class MissingImageTest(unittest.TestCase):
    """An absent pinned image may skip only when nothing demanded the running check."""

    ABSENT = "Error response from daemon: No such image: example/server@sha256:00"

    def test_absent_image_skips_with_what_was_not_proven(self) -> None:
        """The reason names the image, the unproven service, the fetch and the demand flag."""
        reason = missing_image_reason("example/server", self.ABSENT, {})
        self.assertIsNotNone(reason)
        self.assertIn("example/server", reason)
        self.assertIn("nothing was proven", reason)
        self.assertIn("--gpu-check", reason)
        self.assertIn(f"{DEMAND_FLAG}=1", reason)

    def test_hosted_ci_flags_do_not_demand_the_image(self) -> None:
        """Hosted runners set these and hold no copy of the image, so they still skip."""
        environ = {REQUIRE_FLAG: "1", "GITHUB_ACTIONS": "true"}
        self.assertIsNotNone(missing_image_reason("example/server", self.ABSENT, environ))

    def test_demand_turns_an_absent_image_into_a_failure(self) -> None:
        """Only the exact value demands the check, matching the other PACKAGING_* flags."""
        self.assertIsNone(missing_image_reason("example/server", self.ABSENT, {DEMAND_FLAG: "1"}))
        for value in ("0", "", "true"):
            with self.subTest(value=value):
                self.assertIsNotNone(missing_image_reason("example/server", self.ABSENT, {DEMAND_FLAG: value}))

    def test_other_docker_errors_never_skip(self) -> None:
        """An unreachable daemon or any unknown error describes a broken host: it fails."""
        for error in ("", "Cannot connect to the Docker daemon at unix:///var/run/docker.sock",
                      "permission denied while trying to connect"):
            with self.subTest(error=error):
                self.assertIsNone(missing_image_reason("example/server", error, {}))


if __name__ == "__main__":
    unittest.main()
