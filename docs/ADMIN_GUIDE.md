# Stonks admin guide

This covers installing Stonks, every config key, permissions, the server account that collects fees, and what to do when a trade gets stuck. Players have their own guide in [PLAYER_GUIDE.md](PLAYER_GUIDE.md).

## Install
1. Back up `plugins/`.
2. Stop the server and copy the Stonks jar into `plugins/`.
3. Start the server. On first start Stonks creates `plugins/Stonks/` with `config.yml`, `companies/`, `players/` and `transactions/`, plus an example company, Blocks Corp (id 0, risk 3, 100 shares at 40).
4. Check the console for `Fresh installation; no data migration needed.` and no `ERROR` or `SEVERE` lines from Stonks. Version 2.10.0 printed a harmless `not able to migrate the data from version 1.0.0` warning on a brand new install; 2.10.1 fixed it, and it never touched any data.
5. In game, `/invest` should open the menu and `/invest diagnostics adapters` should list Vault as `AVAILABLE`.

Requirements: Java 21, Vault and an economy plugin. QuickShop-Hikari, ExcellentShop and Towny are optional.

### Updating
Stop the server, back up `plugins/Stonks/`, replace the old jar (don't keep two Stonks jars in `plugins/`) and start. On a server that already has Stonks data the console says `No data migration needed from X to Y`, or runs the migration and reports it. Your config keeps its values; new keys are added with their defaults.

### Removing
To remove Stonks, stop the server, take the jar out and move `plugins/Stonks/` somewhere safe. Don't delete that folder: it holds every player's shares and the transaction journal, and putting it back restores everything. Removing the plugin does not refund what players paid for shares.

## Admin commands

| Command | Permission | What it does |
|---|---|---|
| `/invest reload` | `blockstreet.admin.reload` | Reloads `config.yml`. In game only. |
| `/invest diagnostics adapters` | `stonks.admin.reconcile` | Shows each integration (Vault, Towny, QuickShop, AdvancedJobs) and its status. |
| `/invest diagnostics town <town>` | `stonks.admin.reconcile` | Shows what Stonks can read about a Towny town. Read only. |
| `/invest reconcile list` | `stonks.admin.reconcile` | Lists trades that need review. |
| `/invest reconcile show <id>` | `stonks.admin.reconcile` | Shows one of them. |
| `/invest reconcile resolve <id> acknowledge:<note>` | `stonks.admin.reconcile` | Records that staff reviewed it. |
| `/invest reconcile investigate list` | `stonks.admin.investigate` | Lists trades under investigation. |
| `/invest reconcile investigate inspect <id>` | `stonks.admin.investigate` | Shows a trade and each of its steps. |
| `/invest reconcile investigate evidence <id> <effect-id> <type> <reference> <key> <statement...>` | `stonks.admin.investigate` | Attaches a note about what you found. It does not move money. |
| `/invest company shop unlink` | `stonks.admin.reconcile` | Admins can unlink any player's chest shop, not just their own. |

`/invest admin create` and `/invest admin delete` exist but answer that they are disabled. Companies are created by players, who pay for them.

`diagnostics`, `reconcile list`, `reconcile show` and `reconcile resolve` also work from the console. Everything else, `reload` included, answers "Only players can use this command." there, so run `/invest reload` from in game. The `investigate` commands need a player, so the evidence is tied to who recorded it.

## Permissions

Player permissions, all `true` by default:
- `stonks.use`: every `/invest` command.
- `stonks.command.company`: the `/company` menu.
- `stonks.command.company.shop`: linking chest shops.
- `stonks.ui.companies`, `stonks.ui.portfolio`, `stonks.ui.notifications`: the menu pages.
- `blockstreet.command.company`, `blockstreet.command.companies`, `blockstreet.command.portfolio`, `blockstreet.command.notification`: the matching commands. The `blockstreet` prefix is kept from older versions.

Admin permissions, `op` by default:
- `stonks.admin.reconcile`, `stonks.admin.investigate`, `blockstreet.admin.reload`.
- `blockstreet.admin.command.create`, `blockstreet.admin.command.delete` (the disabled admin commands).

With LuckPerms, give the admin nodes to the staff group only.

## Config

All keys live under `Stonks:` in `plugins/Stonks/config.yml`. Run `/invest reload` in game or restart after editing. `Treasury.BackServerShop` is the exception: it only takes effect on a restart.

| Key | Default | Meaning |
|---|---|---|
| `Version` | `2.0.0` | Internal. Don't change it. |
| `InterestInterval` | `39` | Minutes between price updates. At 10 minutes almost every company went bankrupt within a month in our simulation; at 39 about two thirds of risk 1 companies do. |
| `Shadow.Observation.Enabled` | `false` | Records Towny town data for future use. Read only, no effect on money. |
| `Shadow.Observation.IntervalMinutes` | `10` | How often it records. |
| `Updates.Reminder` | `true` | Tells ops about new versions when they join. |
| `StockCrash.Enabled` | `true` | Allows crashes. |
| `StockCrash.Aggressiveness` | `0.7` | 0 to 1. Higher means more frequent crashes, and risk multiplies it. |
| `StockCrash.RemoveBankrupt` | `false` | Currently ignored: bankrupt companies are kept and marked bankrupt, because removing them would need a payout to every shareholder. |
| `MarketImpactPerShare` | `0.00003` | How much each traded share moves the price. 1000 shares is about 3%. 0 to 0.01. |
| `Taxes.Trading` | `0.01` | Fee per trade as a fraction of its value (1%). Below 0.5. |
| `Taxes.TradingMinimum` | `1.0` | Smallest fee per trade. Stops tiny orders from dodging the fee. |
| `Taxes.CompanyCreation` | `0.1` to `0.5` | Creation tax per risk level, as a fraction of the company's value. |
| `Treasury.Account` | `""` | Server account that receives fees and creation tax. Empty: that money leaves the economy. See below. |
| `Treasury.BackServerShop` | `false` | Makes ExcellentShop's `/shop` trade against that account. Needs a restart. See below. |
| `Limits.MinSharePrice` | `30` | Lowest starting share price when a player creates a company. `-1` turns it off. |
| `Limits.MaxSharePrice` | `50` | Highest starting share price. `-1` turns it off. |
| `Limits.MinBalance` | `0.1` | Not enforced yet. |
| `Limits.MaxSharesPerPlayer` | `1000` | Most shares one player can hold across all companies. `-1` turns it off. |

### How prices behave
Each update moves every company's price by a random step. A company with risk `r` and starting price `p` stays roughly between `p × 0.6 / r` and `p × 3 × r`. Near the bottom it can crash, and a company at zero is bankrupt and can't be traded.

Trades move the price too: buying pushes it up by `MarketImpactPerShare` per share, selling pushes it down, and each order fills at the average price across its own move. Together with the fee, this means a buy followed by a sell always loses money, so players can't farm gold by trading in a loop.

## The server account

By default the trading fee and the creation tax are simply removed from the economy. If you set `Treasury.Account`, Stonks deposits them into that account instead. You can then also have Towny and ExcellentShop use the same account, so the server pays out of money it collected and doesn't create new gold.

Stonks keeps its own ledger in `plugins/Stonks/treasury/ledger.yml`:
- `credited`: total deposited so far.
- `owed`: collected but not deposited yet. It covers fractions smaller than one coin (the economy only takes whole gold) and any deposit that failed, for example because the account didn't exist yet. Stonks retries on the next fee and on startup, so nothing is lost.
- `paid`: total paid out to `/shop` sellers.
- `unbacked`: payouts the account couldn't cover. This should stay at 0.

### Setup with TheNewEconomy, Towny and ExcellentShop
Do this on a test copy first, then on the live server with it stopped.

If CMI (or another plugin) also registers `/money`, it takes over the plain command. Use TNE's own with its prefix, `theneweconomy:money ...`, as the steps below do.

1. **TheNewEconomy**: in `plugins/TheNewEconomy/config.yml`, under `Core.Server.Account`, set `Enabled: true`. Keep `Name: Server_Account`. `Balance` is the starting gold, but TNE only applies it when it creates the account. If the account already exists the value is ignored, and you add gold by hand: `theneweconomy:money give Server_Account <amount>`.
2. Start the server once so TNE creates the account. Check it with `theneweconomy:money other Server_Account`, which prints `Total: N G`.
3. **Stonks**: set `Treasury.Account: "Server_Account"` and run `/invest reload` in game (or restart). From then on fees and creation tax go to the account.
4. **Towny** (optional): in `plugins/Towny/settings/config.yml`, under `economy.closed_economy`, set `enabled: 'true'`, `server_account: Server_Account`, and `server_account_uuid` to the UUID of TNE's account. Find it in `plugins/TheNewEconomy/accounts/`: open the file containing `Name: Server_Account` and copy its `ID:`. Some setups find the account by name even with Towny's default UUID, but on others the money goes nowhere and TNE logs "Unable to locate account", so set the real UUID. Once it works, town and nation costs, upkeep and taxes land in the server account. Watch the balance across one Towny new day to confirm.
5. **ExcellentShop** (optional): set `Treasury.BackServerShop: true` and restart. On start the console prints `Server shop (/shop) now pays and charges through the treasury account.` If it prints `Server shop is not linked to the treasury (ExcellentShop API not recognised)` instead, your ExcellentShop version isn't supported and `/shop` keeps creating gold as before. Tested with ExcellentShop 5.1.7 and nightcore 2.16.7. Use Stonks 2.10.2 or later for this: in 2.10.0 and 2.10.1 the price preview in the buy menu also counted as a purchase, so every `/shop` purchase went into the account twice.

   With the bridge on, every sale to the virtual shop (`/shop`, its sell menu `/sellgui`, and `/sellall`) is paid from the server account, and every purchase from `/shop` goes into it. If the account can't cover a sale, the sale is refused, the player keeps the items and sees "The server shop is out of gold right now. Try again later." Player chest shops and the auction house are not affected.
6. Check `theneweconomy:money other Server_Account`, `plugins/Stonks/treasury/ledger.yml`, and a `/shop` sale and purchase of one item. The account should move by the same amount the player received or paid.

`/shop` prices are dynamic and fractional, but TNE with item gold only pays whole coins, so the bridge moves the same rounded-down amount the player actually received.

The auction house listing tax (`/ah`, 10% by default) does not go to the account yet; it still leaves the economy.

### Watching it
- `theneweconomy:money other Server_Account` gives the balance. Check it once a day for the first weeks.
- Money comes in from Stonks fees and creation tax, `/shop` purchases and Towny (on our server one Towny new day added about 19,500).
- Money goes out through `/shop` sales. If the balance falls day after day, the ExcellentShop selling prices are too generous for what comes in: lower them or top the account up.
- The ledger's `credited` minus `paid` should match what the account gained from Stonks and `/shop`. Towny deposits go straight to the account and don't appear in the ledger.
- If `unbacked` goes above 0, or `owed` keeps growing, check that the account exists and that `Treasury.Account` matches its name exactly.
- With an empty account, `/shop` refuses sales until the account is topped up.
- Don't rename or delete `Server_Account`. Stonks and Towny find it by name. Stonks would keep its deposits as `owed` and retry, but Towny's would be lost.
- To turn the bridge off, set `Treasury.BackServerShop: false` and restart; `/shop` then pays out of nowhere again. To stop sending fees to the account, set `Treasury.Account: ""` and run `/invest reload`.

## When a trade gets stuck

Every buy, sell, company creation and deletion goes through a journal in `plugins/Stonks/transactions/`. If the server stops partway, that trade becomes `RECONCILIATION_REQUIRED` on the next start. Stonks then pauses trading for that player and that company, so nothing can make it worse. The player sees "Trading is paused ... Nothing was charged." when they try again.

To handle it:
1. `/invest reconcile list` to see what's pending.
2. `/invest reconcile show <id>` to see who, what company, how much, and which steps completed.
3. Check the player's real balance and shares, and fix the balance by hand in the economy plugin if needed. Write down what you did.
4. `/invest reconcile resolve <id> acknowledge:<what you did>`.

Make sure staff know this exists: a player saying "Trading is paused" is usually the only sign of a stuck trade.

In this version acknowledging records the review but keeps the player paused. Automatic release after a manual repair is turned off on purpose. These should be rare: in testing with up to 30 bots and some killed mid-purchase, no trade was left pending.

## Files
- `plugins/Stonks/companies/<id>.yml`: one file per company.
- `plugins/Stonks/players/`: each player's shares.
- `plugins/Stonks/transactions/`: the journal.
- `plugins/Stonks/treasury/ledger.yml`: the server account ledger.
- `plugins/Stonks/shops/links.yml`: chest shop links.

Back up the whole `plugins/Stonks/` folder with the rest of the server.
