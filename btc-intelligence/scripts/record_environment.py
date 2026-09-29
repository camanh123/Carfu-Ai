"""Record the installed NautilusTrader build used by Phase 0."""

import json
import platform
import sys
from importlib.metadata import version
from pathlib import Path

import nautilus_trader


def main() -> None:
    payload = {
        "nautilus_version": nautilus_trader.__version__,
        "nautilus_distribution_version": version("nautilus_trader"),
        "python_version": platform.python_version(),
        "python_implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "installation_method": (
            "pip install nautilus_trader==1.231.0 "
            "from PyPI as the binary wheel "
            "nautilus_trader-1.231.0-cp312-cp312-manylinux_2_35_x86_64.whl"
        ),
        "selection": {
            "pinned": "1.231.0",
            "pypi_latest_stable": "1.231.0",
            "latest_release_candidate_not_pinned": "2.0.0rc5",
            "rationale": (
                "1.231.0 is the latest stable PyPI release and publishes a "
                "CPython 3.12 manylinux x86_64 wheel. 2.0.0rc5 is a pre-release. "
                "Upstream documents release candidates as community testing builds "
                "and does not recommend them for production."
            ),
        },
        "live_trading": False,
    }
    destination = Path(__file__).resolve().parents[1] / "config" / "environment.json"
    destination.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(destination)
    print(sys.version)


if __name__ == "__main__":
    main()
