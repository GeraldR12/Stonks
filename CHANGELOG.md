# Changelog

All notable changes to Stonks. Versions follow [semantic versioning](https://semver.org/).

## [2.10.2] - 2026-11-13

### Fixed
- `/shop` purchases were added to the server account twice. ExcellentShop fires its "transaction completed" event for the price preview in the buy menu as well as for the real purchase; the preview is now ignored.

## 2.10.1 - 2026-11-13

### Fixed
- The first start on a brand new server logged a false "not able to migrate the data from version 1.0.0" warning. No data was touched.

## 2.10.0 - 2026-11-13

### Added
- Server account (`Treasury.Account`): trading fees and the company creation tax go to an economy account instead of leaving the economy. A ledger in `treasury/ledger.yml` keeps the totals and retries failed deposits.
- ExcellentShop bridge (`Treasury.BackServerShop`): sales to `/shop`, `/sellgui` and `/sellall` are paid from the server account, purchases go into it, and a sale the account can't cover is refused.
- Chest shop links: `/invest company shop link|unlink|list` for QuickShop-Hikari shops.
- Player and admin guides in `docs/`.

### Changed
- ExcellentShop added to `softdepend`.

## 2.9.1 - 2026-11-13

### Added
- Transaction journal for every buy, sell, company creation and deletion. A trade cut off by a crash is held for staff review (`/invest reconcile`) and never half applied.
- Menus rebuilt on the plain Bukkit inventory API: market, portfolio, company details, company management and notifications.
- Share price limits for new companies (`Limits.MinSharePrice`, `Limits.MaxSharePrice`).
- Market impact: each trade moves the price, and orders fill at the average price across that move.

### Fixed
- Buying and selling straight back could make money. Fees and market impact now make every round trip lose gold.

### Changed
- Default price update interval is 39 minutes.

## Earlier versions

Stonks grew out of [BlockStreet](https://github.com/Hugo1307/BlockStreet). See the repository history for older changes.

[2.10.2]: https://github.com/GeraldR12/Stonks/releases/tag/v2.10.2
