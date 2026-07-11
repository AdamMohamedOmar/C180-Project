from click.testing import CliRunner

from kl.cli import cli


def test_ingest_reports_not_implemented():
    result = CliRunner().invoke(cli, ["ingest", "some_dir"])
    assert result.exit_code == 0
    assert "not implemented yet" in result.output.lower()
    assert "phase 5" in result.output.lower()


def test_report_reports_not_implemented():
    result = CliRunner().invoke(cli, ["report", "ride_001"])
    assert result.exit_code == 0
    assert "not implemented yet" in result.output.lower()


def test_trend_reports_not_implemented():
    result = CliRunner().invoke(cli, ["trend"])
    assert result.exit_code == 0
    assert "phase 6" in result.output.lower()
