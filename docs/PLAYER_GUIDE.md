# Stonks player guide

Stonks lets you invest your gold in companies run by other players. You buy shares, the price moves over time, and you sell when you like the price. You can also start your own company.

All money is the server's normal money. On servers with item gold (TheNewEconomy), it comes out of and goes back into your inventory as ingots and blocks, so keep some free inventory space before you sell.

## The quick version
1. `/invest companies` to see what's on the market.
2. `/invest company info <id>` to look at one company: price, risk, history, shareholders.
3. `/invest buy <id> <amount>` to buy shares.
4. `/invest portfolio` to see what you own and what you paid.
5. `/invest sell <id> <amount>` to sell.

Or type `/invest` and do all of it from the menu.

## Commands

| Command | What it does |
|---|---|
| `/invest` | Opens the menu: Market, My Portfolio, My Companies, Notifications, Help. |
| `/invest help` | Lists the commands you can use. Click a line to put it in your chat box. |
| `/invest companies` | Lists every company with its id, share price and shares available. |
| `/invest company info <id>` | Shows one company in detail. |
| `/invest buy <id> <amount>` | Buys shares of a company. |
| `/invest sell <id> <amount>` | Sells shares you own. |
| `/invest portfolio` | Shows your shares and the average price you paid. |
| `/invest company create <name> <risk> <shares> <price> [icon]` | Starts a company (see below). |
| `/invest company delete <id>` | Closes your company and pays you its value. |
| `/invest company shop link <id>` | Links the chest shop you're looking at to your company. |
| `/invest company shop unlink` | Unlinks the chest shop you're looking at. |
| `/invest company shop list` | Lists your linked chest shops. |
| `/invest notification <type>` | Turns a chat notification on or off. Types: `stocks_update`, `company_bankrupt`. |
| `/invest info` | Shows the plugin version. |
| `/company` | Opens your companies in the menu. |

`<id>` is the company number shown by `/invest companies`. Every `/invest` command has to be typed in game; none of them work from a command block.

## Using the menu

`/invest` opens a chest menu.

- **Market** lists the companies. Right-click a company to buy 1 share, Shift+Right-click for 10, Shift+Left-click for 100. Left-click opens its details page (or the management page, if it's your company), with buttons to buy 1, 10 or 100 and to sell 1, 10 or all.
- **My Portfolio** lists what you own with the current value and your average buy price. Right-click sells 1 share, Shift+Right-click sells all of that company.
- **My Companies** lists the companies you created, with a delete button that asks you to confirm.
- **Notifications** switches the chat messages on and off.

Menu clicks go through the same checks and fees as the commands.

## Buying and selling

You don't trade directly with other players. You buy from the market and sell back to it, and everyone sees the same price.

You pay the share price times the number of shares, plus a fee. When you sell you get the share price times the number of shares, minus the same fee.

The fee is 1% of the trade with a minimum of 1 gold (your server may use different numbers). On item gold servers amounts are rounded down to whole gold. Fees go to the server account, which the server uses to pay players who sell items in `/shop`.

Each trade also moves the price a little in the direction you traded: buying pushes it up, selling pushes it down. A big order gets the average price across that move, not the price before it. Because of this and the fee, buying and selling straight away always loses a bit. You make money only when the market moves your way.

You can't own more than 1000 shares in total across all companies (servers can change this).

Messages you might see:
- "You do not have enough money. You need X to do that." You're short on gold for the shares plus the fee.
- "You don't have enough stocks." You tried to sell more than you own.
- "This company doesn't have enough stocks to sell." The company has fewer free shares than you asked for.
- "You cannot own more than X shares in total." You hit the share limit.
- "You cannot buy stocks from a bankrupt company."
- "This sale is too small ..." The sale is worth less than the fee.
- "Trading is paused ... Nothing was charged." A trade of yours was interrupted earlier (a crash or a restart in the middle of it) and staff need to look at it first. Your gold and shares are safe. Ask a staff member.

## How prices move

Every 39 minutes (the server can change this) each company's price goes up or down by a random amount. The company's risk, from 1 to 5, sets how far:

- Risk 1 moves gently and stays closer to its starting price.
- Risk 5 can climb much higher, and can also fall much further.

Prices can also crash. A company whose price reaches zero is bankrupt: its shares are worth nothing, can't be bought or sold, and the company can't be deleted for a payout. Turn on the `company_bankrupt` notification if you want to hear about it.

`/invest company info <id>` shows the price history, so you can see how a company has behaved before you buy. Spreading your gold over a few companies is safer than putting it all in one.

## Starting a company

```
/invest company create <name> <risk> <shares> <price> [icon]
```

- `name`: must be unique (case and spaces don't count, so `Acme` and ` acme ` are the same).
- `risk`: 1 to 5.
- `shares`: how many shares the company has.
- `price`: starting price per share. On this server it must be between 30 and 50.
- `icon`: optional item name for the menu, like `DIAMOND`.

You pay the company's value (shares times price) plus a creation tax of 10% for risk 1, 20% for risk 2, and so on up to 50% for risk 5. The tax goes to the server account. You then own all the shares and can sell them to the market whenever you want, like any other shares.

Example: `/invest company create Acme 1 10 40` costs 400 for the shares plus 40 tax, so 440 in total.

To close a company you must own all of its shares. `/invest company delete <id>` (or the button in My Companies) pays you the current value of all shares and removes the company. A bankrupt company is worth nothing, so deleting it pays nothing and is refused.

## Linking chest shops

If you run a QuickShop chest shop and own a company, you can link the two:

1. Look at your chest shop from within 6 blocks.
2. `/invest company shop link <company id>`.

You must own both the shop and the company, and the company can't be bankrupt. A shop can be linked to one company at a time; unlink it first to move it. Deleting the shop, or the company going bankrupt or being deleted, removes the link.

For now a link only records which shops belong to which company. It doesn't change the share price yet. A later version will use it.

## Selling items to /shop

This part isn't Stonks, but it's connected. When you sell items to the server shop (`/shop`, `/sellgui`, `/sellall`), the gold comes out of the server account, the same account that collects Stonks fees and Towny costs. When you buy from `/shop`, your gold goes into it.

If the account is empty, the server shop refuses the sale and says "The server shop is out of gold right now. Try again later." Your items stay with you. Try again later, after the account has collected more. Player chest shops are not affected.

## Notifications

`/invest notification stocks_update` turns the price update message on or off. `/invest notification company_bankrupt` does the same for bankruptcies. You can also switch them in the Notifications menu.
