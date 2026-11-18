# Changelog

All notable changes to Stonks. Versions follow [semantic versioning](https://semver.org/).

## [2.12.0-rc.1] - 2026-11-18

- Each company now has funds. The gold paid to create a company and to buy its shares goes into them, and selling shares is paid out of them. A sale the funds cannot cover is refused before any share moves. Stonks no longer creates or destroys gold in share trades.
- Owners can deposit their own gold (`/invest company deposit`) and pay dividends per share to every shareholder (`/invest company dividend`). `Stonks.Taxes.Dividend` sends part of each dividend to the treasury. There is no personal withdrawal.
- A linked QuickShop selling shop gives `Stonks.CompanyFunds.ShopRevenueShare` (default 5%) of each sale to the company, charged to the shop owner.
- When the owner removes a company, they receive its funds instead of a payout at share price. When a company goes bankrupt or staff removes it, its funds go to the treasury account.
- Staff can list pending payouts, move gold from the treasury into a company, and resolve a pending payout (`/invest admin funds list|grant|resolve`). A payout with an unconfirmed Vault outcome pauses that company until it is resolved.
- Companies created before this version start with no funds, so their shares can be sold only once the funds hold enough. `Stonks.CompanyFunds.Enabled: false` restores the previous behaviour.

## [2.11.0-rc.3] - 2026-11-18

- With The New Economy installed, a backed ExcellentShop trade is settled by TNE's own receipt for that player: it must succeed and move exactly the trade's gold, or the trade stays pending for review. Without TNE the balance check from rc.2 still applies and the log says so.
- The server shop refuses trades from offline players while it is backed by the treasury.
- Company creation and deletion are written to the transaction journal step by step, like share orders.

## [2.11.0-rc.2] - 2026-11-18

- Backed ExcellentShop purchases credit the treasury only after the expected player debit is observed. Checkout previews and cancelled purchases have no treasury effect.
- A late-cancelled sale with an unchanged player balance returns its reservation. An unresolved balance difference pauses backed trades and remains in the ledger after restart.

## [2.11.0-rc.1] - 2026-11-18

- The market has a bankruptcy archive. Closed positions show their loss and release share capacity while keeping the original records.
- Buy and sell buttons open a quote with quantity, average price, fee and final gold amount. A changed or expired quote requires a fresh confirmation.
- The inventory menu now includes a guide, company creation, price history, custom quantities and shop controls. New text can be translated in `messages.yml`.
- The scheduler skips companies with a transaction awaiting reconciliation. Creation and deletion now keep unconfirmed Vault responses pending.
- ExcellentShop sales are refused if the server treasury debit cannot be confirmed. Pending treasury operations survive restart and require review before retrying.
- Linked QuickShop trades can be observed in company details. They do not change prices or payouts.

## [2.10.5] - 2026-11-14

### Fixed
- Breaking a ticker sign left its record behind. The plugin looked for "[Stonks]" on the broken sign, but a ticker shows the company name, so the record was never removed. Records are now removed by position, whatever the sign says. A break cancelled by a protection plugin (Towny, for example) keeps the record.
- A sign placed where an old one stood got a second record for the same block. Placing a sign now replaces the record at that block, and duplicates left by older versions are cleaned up the first time someone clicks the sign.
- Updating signs loaded the chunks they were in. Signs in unloaded chunks are now skipped and brought up to date when their chunk loads. A record whose block is no longer a sign (exploded, burnt, replaced) is removed.
- Clicking any sign, or loading any chunk, read every sign file from disk. The plugin now keeps an in-memory list of sign positions.

### Changed
- Creating a `[Stonks]` sign needs `stonks.sign.create` (default: everyone). Naming a company that doesn't exist now says so instead of doing nothing.
- `/invest company create` explains what is wrong: an icon that isn't an item (with example IDs), a risk outside 1 to 5, a name longer than 16 characters (the most a sign line shows), or a name with spaces.

## [2.10.4] - 2026-11-14

### Changed
- Shares of companies you own no longer count towards `Limits.MaxSharesPerPlayer`. A player who creates a company starts with all of its shares, so a company bigger than the limit used to block its owner from buying anything else.

### Fixed
- A buy that went over the share limit charged the player and then refunded them ("Purchase could not be completed; your payment was refunded."). The limit is now checked before any money moves, and the message says how many shares you have and how many more you can buy.

## [2.10.3] - 2026-11-13

### Added
- Price history files: every price change is appended to `plugins/Stonks/history/<companyId>.csv` with the time, the source (`update`, `crash`, `buy`, `sell`), the shares traded, the old and new price and the variation. No player names. Turn it off with `History.Enabled: false`.

### Fixed
- Each timed update reversed the stored price history, so `/invest company info` showed it out of order and, once a company reached 500 entries, a recent price could be dropped instead of the oldest. History is now kept oldest first, and histories saved out of order by older versions are sorted again when loaded.

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

[2.10.3]: https://github.com/GeraldR12/Stonks/releases/tag/v2.10.3
[2.10.2]: https://github.com/GeraldR12/Stonks/releases/tag/v2.10.2
