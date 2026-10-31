"""User-facing product identity.

Internal Python namespace remains ``astra``. This module is the single
canonical name shown to people. Not a schema field. Not an authority.
"""

from __future__ import annotations

from astra import __version__

PRODUCT_NAME = "Primus"
PRODUCT_TITLE = "Primus"
PRODUCT_DESCRIPTION = "Primus Personal AI Operating System"
CORE_NAME = "Primus Core"
GREETING = "Hello. I'm Primus."
PRODUCT_VERSION = __version__

__all__ = [
    "CORE_NAME",
    "GREETING",
    "PRODUCT_DESCRIPTION",
    "PRODUCT_NAME",
    "PRODUCT_TITLE",
    "PRODUCT_VERSION",
]
