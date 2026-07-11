from __future__ import annotations

import click


@click.group()
def cli() -> None:
    """KompressorLink pipeline CLI."""


@cli.command()
@click.argument("directory", type=click.Path(exists=False))
def ingest(directory: str) -> None:
    """Raw long CSV -> canonical wide parquet (Phase 5)."""
    click.echo(f"kl ingest: not implemented yet (Phase 5). directory={directory}")


@cli.command()
def features() -> None:
    """Per-ride aggregate features (Phase 5)."""
    click.echo("kl features: not implemented yet (Phase 5)")


@cli.command()
def baseline() -> None:
    """Per-regime healthy-ride envelopes (Phase 6)."""
    click.echo("kl baseline: not implemented yet (Phase 6)")


@cli.command()
def trend() -> None:
    """Drift forecasters, gated on beating persistence (Phase 6)."""
    click.echo("kl trend: not implemented yet (Phase 6)")


@cli.command()
@click.argument("ride")
def report(ride: str) -> None:
    """Markdown health report for one ride (Phase 5)."""
    click.echo(f"kl report: not implemented yet (Phase 5). ride={ride}")


if __name__ == "__main__":
    cli()
