package me.geraldr12.utils;

import com.google.inject.Inject;
import me.geraldr12.Stonks;
import me.geraldr12.enums.ConfigurationFiles;
import lombok.Getter;

@Getter
public class Messages {

    private static final java.util.Map<String, String> UI_DEFAULTS = java.util.Map.ofEntries(
            java.util.Map.entry("fundsBalance", "§7Funds of §f{0}§7: §6{1}"),
            java.util.Map.entry("fundsDisabled", "§cCompany funds are turned off on this server."),
            java.util.Map.entry("fundsPendingReview", "§eThis company has a payout awaiting staff review; trading and funds actions are paused."),
            java.util.Map.entry("fundsNeedsReview", "§cCRITICAL: this funds operation needs staff review. Nothing will be retried automatically."),
            java.util.Map.entry("fundsInvalidAmount", "§cEnter an amount the economy can pay (at least 1 unit)."),
            java.util.Map.entry("fundsRejected", "§cThe economy refused this operation. Nothing changed."),
            java.util.Map.entry("fundsDeposited", "§aDeposited §6{0}§a. Company funds: §6{1}"),
            java.util.Map.entry("fundsDividendPaid", "§aPaid §6{0}§a in dividends to §f{1}§a shareholder(s). Company funds: §6{2}"),
            java.util.Map.entry("fundsDividendTooSmall", "§cThat dividend is too small to give every shareholder at least 1 unit."),
            java.util.Map.entry("fundsInsufficient", "§cThe company only has §6{0}§c in funds."),
            java.util.Map.entry("fundsInsufficientForSale", "§cThe company cannot buy these shares back right now: its funds are §6{0}§c. Try fewer shares or wait for the company to earn more."),
            java.util.Map.entry("uiCompanyFunds", "§7Company funds: §6{0}"),
            java.util.Map.entry("uiShopObserved", "§7Linked shop trades in the last hour: §f{0}"),
            java.util.Map.entry("uiShopObservedHint", "§7Live activity since server start, with no effect on price."),
            java.util.Map.entry("uiTradeCustomBuy", "§aBuy a custom amount"),
            java.util.Map.entry("uiTradeCustomSell", "§cSell a custom amount"),
            java.util.Map.entry("uiTradeAmountPrompt", "§eEnter the number of shares in chat, or type cancel."),
            java.util.Map.entry("uiInputInvalid", "§cEnter a valid value and try again."),
            java.util.Map.entry("uiCreateTitle", "Create Company"),
            java.util.Map.entry("uiCreateButton", "§aCreate a company"),
            java.util.Map.entry("uiCreateName", "§fCompany name: {0}"),
            java.util.Map.entry("uiCreateNamePrompt", "§eType a company name of up to 16 characters, with no spaces, or type cancel."),
            java.util.Map.entry("uiCreateRisk", "§fRisk: {0}"),
            java.util.Map.entry("uiCreateRiskHint", "§7Left-click to increase, right-click to decrease."),
            java.util.Map.entry("uiCreateShares", "§fShares: {0}"),
            java.util.Map.entry("uiCreateSharesPrompt", "§eType the number of shares to issue, or type cancel."),
            java.util.Map.entry("uiCreatePrice", "§fInitial price: {0}"),
            java.util.Map.entry("uiCreatePricePrompt", "§eType a positive price per share in gold, or type cancel."),
            java.util.Map.entry("uiCreateCost", "§7Capital plus creation tax: §f{0} gold"),
            java.util.Map.entry("uiCreateConfirm", "§aCreate company"),
            java.util.Map.entry("uiCreateUnset", "Choose a name"),
            java.util.Map.entry("uiCompanyShops", "§aLinked chest shops"),
            java.util.Map.entry("uiCompanyShopsCount", "§7Linked shops: {0}"),
            java.util.Map.entry("uiCompanyShopTarget", "§7Look at a chest shop before opening this menu."),
            java.util.Map.entry("uiCompanyShopLink", "§aLink the targeted shop"),
            java.util.Map.entry("uiCompanyShopUnlink", "§cUnlink the targeted shop"),
            java.util.Map.entry("uiCompanyShopList", "§eList my linked shops"),
            java.util.Map.entry("uiMyCompanyUnavailable", "§cCompany management is unavailable."),
            java.util.Map.entry("uiQuoteTitle", "Review order"),
            java.util.Map.entry("uiQuoteBuy", "§aBuy {0} shares in {1}"),
            java.util.Map.entry("uiQuoteSell", "§cSell {0} shares in {1}"),
            java.util.Map.entry("uiQuoteAverage", "§7Average price: §f{0} gold"),
            java.util.Map.entry("uiQuoteFee", "§7Trading fee before rounding: §f{0} gold"),
            java.util.Map.entry("uiQuoteCharge", "§7You pay: §f{0} gold"),
            java.util.Map.entry("uiQuotePayout", "§7You receive: §f{0} gold"),
            java.util.Map.entry("uiQuoteAfter", "§7Estimated price after order: §f{0} gold"),
            java.util.Map.entry("uiQuoteConfirm", "§aConfirm order"),
            java.util.Map.entry("uiQuoteUnavailable", "§cQuote unavailable for this amount"),
            java.util.Map.entry("uiQuoteChanged", "§eThe quote changed. Review the new total before confirming."),
            java.util.Map.entry("uiQuoteExpired", "§eThe quote expired. Review the new total before confirming."),
            java.util.Map.entry("uiQuoteCancel", "§cBack"),
            java.util.Map.entry("uiDetailHistory", "§6Price history"),
            java.util.Map.entry("uiDetailHistoryEntry", "§7{0} UTC · §f{1} gold"),
            java.util.Map.entry("uiDetailHistoryEmpty", "§7No price history"),
            java.util.Map.entry("uiCompanyDeleteUnavailable", "§cTrading has ended for this company."),
            java.util.Map.entry("uiCompanySuspended", "§eTrading unavailable"),
            java.util.Map.entry("uiMarketArchive", "§6Bankrupt companies"),
            java.util.Map.entry("uiMarketArchiveHint", "§7View closed listings and their history."),
            java.util.Map.entry("uiMarketActive", "§aActive companies"),
            java.util.Map.entry("uiNoActiveCompanies", "§7No active companies"),
            java.util.Map.entry("uiNoBankruptCompanies", "§7No bankrupt companies"),
            java.util.Map.entry("uiBankruptAt", "§7Bankruptcy (UTC): §f{0}"),
            java.util.Map.entry("uiBankruptcyUnknown", "§7Bankruptcy date unavailable"),
            java.util.Map.entry("uiBankruptcyReason", "§7Reason: market crash"),
            java.util.Map.entry("uiBankruptcyLegacyReason", "§7Reason: recorded bankruptcy"),
            java.util.Map.entry("uiBankruptRecent", "§cRecently closed"),
            java.util.Map.entry("uiBankruptPreviousPrice", "§7Last price before closing: §f{0} gold"),
            java.util.Map.entry("uiBankruptPosition", "§7Position closed at zero. §f{0} gold lost."),
            java.util.Map.entry("uiCompanyOpenDetails", "§eClick to view details"),
            java.util.Map.entry("uiMyCompaniesTitle", "My Companies"),
            java.util.Map.entry("uiMyCompaniesEmpty", "§7You do not own any companies"),
            java.util.Map.entry("uiPortfolioEmpty", "§7You have no positions"),
            java.util.Map.entry("uiGuideTitle", "Stonks Guide"),
            java.util.Map.entry("uiGuideTrade", "§aBuy and sell shares"),
            java.util.Map.entry("uiGuideTradeHint", "§7Open a company, choose an amount and review the quote."),
            java.util.Map.entry("uiGuidePortfolio", "§bReview your portfolio"),
            java.util.Map.entry("uiGuidePortfolioHint", "§7See your holdings, average cost and closed positions."),
            java.util.Map.entry("uiGuideCompany", "§6Manage companies"),
            java.util.Map.entry("uiGuideCompanyHint", "§7Create a company or manage shares, shops and closing."),
            java.util.Map.entry("uiGuideBankruptcy", "§cWhat happens when a company fails?"),
            java.util.Map.entry("uiGuideBankruptcyHint", "§7Trading ends at zero. The listing and losses stay in history."),
            java.util.Map.entry("uiGuideNotifications", "§6Manage notifications"),
            java.util.Map.entry("uiGuideNotificationsHint", "§7Choose which price and bankruptcy alerts you receive."),
            java.util.Map.entry("uiGuideCommands", "§fChat commands"),
            java.util.Map.entry("uiGuideCommandsHint", "§7Optional shortcuts for trading, company management and settings."),
            java.util.Map.entry("uiManageCompany", "§6Manage company"),
            java.util.Map.entry("uiTradePermission", "§cYou do not have permission to trade."),
            java.util.Map.entry("uiMainMenuTitle", "Stonks"),
            java.util.Map.entry("uiMainMarket", "§aMarket"),
            java.util.Map.entry("uiMainPortfolio", "§aMy Portfolio"),
            java.util.Map.entry("uiMainCompanies", "§aMy Companies"),
            java.util.Map.entry("uiMainNotifications", "§6Notifications"),
            java.util.Map.entry("uiMainHelp", "§aHelp"),
            java.util.Map.entry("uiMainClose", "§cClose"),
            java.util.Map.entry("uiMainHelpBuy", "§7/invest buy <id> <amount>"),
            java.util.Map.entry("uiMainHelpSell", "§7/invest sell <id> <amount>"),
            java.util.Map.entry("uiMainHelpPortfolio", "§7/invest portfolio"),
            java.util.Map.entry("uiMainHelpCompanies", "§7/invest companies"),
            java.util.Map.entry("uiMainHelpCreate", "§7/invest company create <name> <risk> <shares> <price>"),
            java.util.Map.entry("uiMainHelpDelete", "§7/invest company delete <id>"),
            java.util.Map.entry("uiDetailTitle", "Company Details"),
            java.util.Map.entry("uiDetailCompanyName", "§6{0}"),
            java.util.Map.entry("uiDetailAvailableShares", "§aAvailable Shares: §7{0}"),
            java.util.Map.entry("uiDetailBuyOne", "§aBuy 1 share"),
            java.util.Map.entry("uiDetailBuyTen", "§aBuy 10 shares"),
            java.util.Map.entry("uiDetailBuyHundred", "§aBuy 100 shares"),
            java.util.Map.entry("uiDetailSellOne", "§cSell 1 share"),
            java.util.Map.entry("uiDetailSellTen", "§cSell 10 shares"),
            java.util.Map.entry("uiDetailSellAll", "§cSell all shares"),
            java.util.Map.entry("uiDeleteTitle", "Confirm Company Deletion"),
            java.util.Map.entry("uiDeleteCompanyName", "§6Delete {0}?"),
            java.util.Map.entry("uiDeleteButton", "§c§lDELETE COMPANY"),
            java.util.Map.entry("uiDeleteDescription", "§7Click to review company deletion."),
            java.util.Map.entry("uiDeleteWarning", "§4This cannot be undone!"),
            java.util.Map.entry("uiDeleteConfirm", "§aConfirm deletion"),
            java.util.Map.entry("uiDeleteCancel", "§cCancel")
    );

    private final ConfigAccessor messagesConfig;

    private String pluginPrefix;
    private String pluginHeader;
    private String pluginFooter;
    private String pluginReload;
    private String menuMainCmd;
    private String menuCompaniesCmd;
    private String menuCompanyCmd;
    private String menuBuyCmd;
    private String menuSellCmd;
    private String menuActionsCmd;
    private String menuCreateCompanyCmd;
    private String menuDeleteCompanyCmd;
    private String menuReloadCmd;
    private String menuTimeCmd;
    private String buyActionsCmd;
    private String sellActionsCmd;
    private String listNextPage;
    private String id;
    private String price;
    private String risk;
    private String availableActions;
    private String actionHistoric;
    private String details;
    private String shares;
    private String totalSharesValue;
    private String companyStatus;
    private String companyStatusBankrupt;
    private String companyStatusTrading;
    private String enabledString;
    private String disabledString;

    private String notificationStocksUpdateName;
    private String notificationCompanyBankruptName;

    private String playerNotFound;
    private String playerOnlyCommand;
    private String noPermission;
    private String nonExistentPage;
    private String missingArguments;
    private String wrongArguments;
    private String invalidCompany;
    private String companyAlreadyExists;
    private String insufficientMoney;
    private String insufficientActions;
    private String playerNoActions;
    private String playerAnyActions;
    private String boughtActions;
    private String soldActions;
    private String createdCompany;
    private String deletedCompany;
    private String cannotBuyBankruptCompany;
    private String cannotDeleteNotOwnedCompany;
    private String cannotOwnMoreThanMaxShares;
    private String notificationToggled;

    private String invalidCmd;
    private String interestRate;
    private String updatedInterestRate;
    private String interestRateTimeLeft;
    private String companyStocksCrashed;
    private String newVersionAvailable;
    private String unknownCommand;

    private String buyCommandDescription;
    private String buyCommandUsage;
    private String sellCommandDescription;
    private String sellCommandUsage;
    private String companyCommandDescription;
    private String companyCommandUsage;
    private String companiesCommandDescription;
    private String companiesCommandUsage;
    private String portfolioCommandDescription;
    private String portfolioCommandUsage;
    private String infoCommandDescription;
    private String infoCommandUsage;
    private String reloadCommandDescription;
    private String reloadCommandUsage;
    private String helpCommandDescription;
    private String helpCommandUsage;
    private String createCompanyCommandDescription;
    private String createCompanyCommandUsage;
    private String deleteCompanyCommandDescription;
    private String deleteCompanyCommandUsage;

    private String uiPreviousPage;
    private String uiNextPage;
    private String uiNavigateBack;
    private String uiPortfolioTitle;
    private String uiInvestmentItemShares;
    private String uiInvestmentItemCurrentValue;
    private String uiInvestmentItemAverageBuyPrice;
    private String uiInvestmentItemSellOne;
    private String uiInvestmentItemSellAll;
    private String uiCompaniesTitle;
    private String uiCompanyItemStatus;
    private String uiCompanyItemSharePrice;
    private String uiCompanyItemRisk;
    private String uiCompanyItemMarketCap;
    private String uiCompanyItemAllTimeVariation;
    private String uiCompanyItemLastVariation;
    private String uiCompanyItemBuyOneShare;
    private String uiCompanyItemBuyTenShares;
    private String uiCompanyItemBuyHundredShares;
    private String uiCheckPortfolioButtonTitle;
    private String uiCheckPortfolioButtonDescription;
    private String uiPortfolioSummaryItemTitle;
    private String uiPortfolioSummaryItemCompanies;
    private String uiPortfolioSummaryItemShares;
    private String uiPortfolioSummaryItemTotalValue;
    private String uiNotificationsTitle;
    private String uiNotificationsItemTitle;
    private String uiNotificationsItemDescription;
    private String uiNotificationsToggleStatus;

    @Inject
    public Messages(Stonks plugin) {
        this.messagesConfig = new ConfigAccessor(plugin, ConfigurationFiles.MESSAGES.getFileName());
        initializeMessages();
    }

    public void reload() {
        this.messagesConfig.reloadConfig();
        initializeMessages();
    }

    public String getMessageByKey(String key) {
        String value = this.messagesConfig.getConfig().getString(key);
        return value != null ? value : UI_DEFAULTS.get(key);
    }

    private void initializeMessages() {
        this.pluginPrefix = messagesConfig.getConfig().getString("pluginPrefix");
        this.pluginHeader = messagesConfig.getConfig().getString("pluginHeader");
        this.pluginFooter = messagesConfig.getConfig().getString("pluginFooter");
        this.pluginReload = messagesConfig.getConfig().getString("pluginReload");
        this.menuMainCmd = messagesConfig.getConfig().getString("menuMainCmd");
        this.menuCompaniesCmd = messagesConfig.getConfig().getString("menuCompaniesCmd");
        this.menuCompanyCmd = messagesConfig.getConfig().getString("menuCompanyCmd");
        this.menuBuyCmd = messagesConfig.getConfig().getString("menuBuyCmd");
        this.menuSellCmd = messagesConfig.getConfig().getString("menuSellCmd");
        this.menuActionsCmd = messagesConfig.getConfig().getString("menuActionsCmd");
        this.menuCreateCompanyCmd = messagesConfig.getConfig().getString("menuCreateCompanyCmd");
        this.menuDeleteCompanyCmd = messagesConfig.getConfig().getString("menuDeleteCompanyCmd");
        this.menuReloadCmd = messagesConfig.getConfig().getString("menuReloadCmd");
        this.menuTimeCmd = messagesConfig.getConfig().getString("menuTimeCmd");
        this.buyActionsCmd = messagesConfig.getConfig().getString("buyActionsCmd");
        this.sellActionsCmd = messagesConfig.getConfig().getString("sellActionsCmd");
        this.listNextPage = messagesConfig.getConfig().getString("listNextPage");
        this.id = messagesConfig.getConfig().getString("id");
        this.price = messagesConfig.getConfig().getString("price");
        this.risk = messagesConfig.getConfig().getString("risk");
        this.availableActions = messagesConfig.getConfig().getString("availableActions");
        this.actionHistoric = messagesConfig.getConfig().getString("actionHistoric");
        this.details = messagesConfig.getConfig().getString("details");
        this.shares = messagesConfig.getConfig().getString("shares");
        this.totalSharesValue = messagesConfig.getConfig().getString("totalSharesValue");
        this.companyStatus = messagesConfig.getConfig().getString("companyStatus");
        this.companyStatusTrading = messagesConfig.getConfig().getString("companyStatusTrading");
        this.companyStatusBankrupt = messagesConfig.getConfig().getString("companyStatusBankrupt");
        this.enabledString = messagesConfig.getConfig().getString("enabledString");
        this.disabledString = messagesConfig.getConfig().getString("disabledString");

        this.notificationStocksUpdateName = messagesConfig.getConfig().getString("notificationStocksUpdateName");
        this.notificationCompanyBankruptName = messagesConfig.getConfig().getString("notificationCompanyBankruptName");

        this.playerNotFound = messagesConfig.getConfig().getString("playerNotFound");
        this.playerOnlyCommand = messagesConfig.getConfig().getString("playerOnlyCommand");
        this.noPermission = messagesConfig.getConfig().getString("noPermission");
        this.nonExistentPage = messagesConfig.getConfig().getString("nonExistantPage");
        this.missingArguments = messagesConfig.getConfig().getString("missingArguments");
        this.wrongArguments = messagesConfig.getConfig().getString("wrongArguments");
        this.invalidCompany = messagesConfig.getConfig().getString("invalidCompany");
        this.companyAlreadyExists = messagesConfig.getConfig().getString("companyAlreadyExists");
        this.insufficientMoney = messagesConfig.getConfig().getString("insufficientMoney");
        this.insufficientActions = messagesConfig.getConfig().getString("insufficientActions");
        this.playerNoActions = messagesConfig.getConfig().getString("playerNoActions");
        this.boughtActions = messagesConfig.getConfig().getString("boughtActions");
        this.soldActions = messagesConfig.getConfig().getString("soldActions");
        this.createdCompany = messagesConfig.getConfig().getString("createdCompany");
        this.deletedCompany = messagesConfig.getConfig().getString("deletedCompany");
        this.cannotBuyBankruptCompany = messagesConfig.getConfig().getString("cannotBuyBankruptCompany");
        this.cannotDeleteNotOwnedCompany = messagesConfig.getConfig().getString("cannotDeleteNotOwnedCompany");
        this.cannotOwnMoreThanMaxShares = messagesConfig.getConfig().getString("cannotOwnMoreThanMaxShares");
        this.notificationToggled = messagesConfig.getConfig().getString("notificationToggled");

        this.invalidCmd = messagesConfig.getConfig().getString("invalidCmd");
        this.interestRate = messagesConfig.getConfig().getString("interestRate");
        this.updatedInterestRate = messagesConfig.getConfig().getString("updatedInterestRate");
        this.playerAnyActions = messagesConfig.getConfig().getString("playerAnyActions");
        this.interestRateTimeLeft = messagesConfig.getConfig().getString("interestRateTimeLeft");
        this.companyStocksCrashed = messagesConfig.getConfig().getString("companyStocksCrashed");
        this.newVersionAvailable = messagesConfig.getConfig().getString("newVersionAvailable");
        this.unknownCommand = messagesConfig.getConfig().getString("unknownCommand");

        this.buyCommandDescription = messagesConfig.getConfig().getString("buyCommandDescription");
        this.buyCommandUsage = messagesConfig.getConfig().getString("buyCommandUsage");
        this.sellCommandDescription = messagesConfig.getConfig().getString("sellCommandDescription");
        this.sellCommandUsage = messagesConfig.getConfig().getString("sellCommandUsage");
        this.companyCommandDescription = messagesConfig.getConfig().getString("companyCommandDescription");
        this.companyCommandUsage = messagesConfig.getConfig().getString("companyCommandUsage");
        this.companiesCommandDescription = messagesConfig.getConfig().getString("companiesCommandDescription");
        this.companiesCommandUsage = messagesConfig.getConfig().getString("companiesCommandUsage");
        this.portfolioCommandDescription = messagesConfig.getConfig().getString("portfolioCommandDescription");
        this.portfolioCommandUsage = messagesConfig.getConfig().getString("portfolioCommandUsage");
        this.infoCommandDescription = messagesConfig.getConfig().getString("infoCommandDescription");
        this.infoCommandUsage = messagesConfig.getConfig().getString("infoCommandUsage");
        this.reloadCommandDescription = messagesConfig.getConfig().getString("reloadCommandDescription");
        this.reloadCommandUsage = messagesConfig.getConfig().getString("reloadCommandUsage");
        this.helpCommandDescription = messagesConfig.getConfig().getString("helpCommandDescription");
        this.helpCommandUsage = messagesConfig.getConfig().getString("helpCommandUsage");
        this.createCompanyCommandDescription = messagesConfig.getConfig().getString("createCompanyCommandDescription");
        this.createCompanyCommandUsage = messagesConfig.getConfig().getString("createCompanyCommandUsage");
        this.deleteCompanyCommandDescription = messagesConfig.getConfig().getString("deleteCompanyCommandDescription");
        this.deleteCompanyCommandUsage = messagesConfig.getConfig().getString("deleteCompanyCommandUsage");

        this.uiPreviousPage = messagesConfig.getConfig().getString("uiPreviousPage");
        this.uiNextPage = messagesConfig.getConfig().getString("uiNextPage");
        this.uiNavigateBack = messagesConfig.getConfig().getString("uiNavigateBack");
        this.uiPortfolioTitle = messagesConfig.getConfig().getString("uiPortfolioTitle");
        this.uiInvestmentItemShares = messagesConfig.getConfig().getString("uiInvestmentItemShares");
        this.uiInvestmentItemCurrentValue = messagesConfig.getConfig().getString("uiInvestmentItemCurrentValue");
        this.uiInvestmentItemAverageBuyPrice = messagesConfig.getConfig().getString("uiInvestmentItemAverageBuyPrice");
        this.uiInvestmentItemSellOne = messagesConfig.getConfig().getString("uiInvestmentItemSellOne");
        this.uiInvestmentItemSellAll = messagesConfig.getConfig().getString("uiInvestmentItemSellAll");
        this.uiCompaniesTitle = messagesConfig.getConfig().getString("uiCompaniesTitle");
        this.uiCompanyItemStatus = messagesConfig.getConfig().getString("uiCompanyItemStatus");
        this.uiCompanyItemSharePrice = messagesConfig.getConfig().getString("uiCompanyItemSharePrice");
        this.uiCompanyItemRisk = messagesConfig.getConfig().getString("uiCompanyItemRisk");
        this.uiCompanyItemMarketCap = messagesConfig.getConfig().getString("uiCompanyItemMarketCap");
        this.uiCompanyItemAllTimeVariation = messagesConfig.getConfig().getString("uiCompanyItemAllTimeVariation");
        this.uiCompanyItemLastVariation = messagesConfig.getConfig().getString("uiCompanyItemLastVariation");
        this.uiCompanyItemBuyOneShare = messagesConfig.getConfig().getString("uiCompanyItemBuyOneShare");
        this.uiCompanyItemBuyTenShares = messagesConfig.getConfig().getString("uiCompanyItemBuyTenShares");
        this.uiCompanyItemBuyHundredShares = messagesConfig.getConfig().getString("uiCompanyItemBuyHundredShares");
        this.uiCheckPortfolioButtonTitle = messagesConfig.getConfig().getString("uiCheckPortfolioButtonTitle");
        this.uiCheckPortfolioButtonDescription = messagesConfig.getConfig().getString("uiCheckPortfolioButtonDescription");
        this.uiPortfolioSummaryItemTitle = messagesConfig.getConfig().getString("uiPortfolioSummaryItemTitle");
        this.uiPortfolioSummaryItemCompanies = messagesConfig.getConfig().getString("uiPortfolioSummaryItemCompanies");
        this.uiPortfolioSummaryItemShares = messagesConfig.getConfig().getString("uiPortfolioSummaryItemShares");
        this.uiPortfolioSummaryItemTotalValue = messagesConfig.getConfig().getString("uiPortfolioSummaryItemTotalValue");
        this.uiNotificationsTitle = messagesConfig.getConfig().getString("uiNotificationsTitle");
        this.uiNotificationsItemTitle = messagesConfig.getConfig().getString("uiNotificationsItemTitle");
        this.uiNotificationsItemDescription = messagesConfig.getConfig().getString("uiNotificationsItemDescription");
        this.uiNotificationsToggleStatus = messagesConfig.getConfig().getString("uiNotificationsToggleStatus");
    }

}
