package process

import database.models.*
import database.models.RevRecTransaction.Status
import framework.Helpers.await
import framework.Instant
import play.api.Logger
import process.Helpers.*
import process.ProcessBillingEvent.*
import services.{ExchangeRate, ExchangeRateService}

case class ProcessCustomerBalance(
  amount: JournalEntryAmount,
  customerBalanceTransaction: Option[CustomerBalanceTransaction]
)

case class ProcessCreditBalanceTransactionOnVoid(
  amount: JournalEntryAmount,
  creditBalanceTransaction: RichCreditBalanceTransaction
)

case class ProcessInvoiceLineItemComponent(
  transaction: RevRecTransaction,
  invoice: RichInvoice,
  invoiceLineItem: RichInvoiceLineItem,
  principleAccount: JournalEntry.Account,
  settlementAmount: Amount,
  presentmentAmount: Amount,
  appliedCustomerBalance: Seq[ProcessCustomerBalance],
  unappliedCustomerBalances: Seq[ProcessCustomerBalance],
  creditBalanceTransactionOnVoids: Seq[ProcessCreditBalanceTransactionOnVoid],
  billingEvents: Seq[BillingEvent],
  syncedAt: Instant
) {
  private[this] val logger = Logger(getClass)

  def generateJournalEntries(): Seq[JournalEntry] = {
    val invoicingEntries = bookArAndPrinciple() ++ bookCustomerBalance()
    (
      invoicingEntries ++ Seq(
        bookBillingEvents(invoicingEntries),
        bookCreditBalanceAppliedOnVoid()
      ).flatten
      )
      .map { entry =>
        entry.copy(
          principleAccount = principleAccount,
          invoiceId = Some(invoice.base.id),
          invoiceLineItemId = Some(invoiceLineItem.base.id),
          invoiceItemId = invoiceLineItem.invoiceItem.map(_.base.id),
          productId = invoiceLineItem.price.map(_.base.productId),
          priceId = invoiceLineItem.base.priceId,
          stripeAccountId = transaction.stripeAccountId,
          liveMode = transaction.liveMode,
          revRecTransactionId = transaction.id,
          revRecTransactionType = transaction.tpe,
          customerId = Some(invoice.base.customerId),
          createdAt = syncedAt
        )
      }
  }

  private[this] def makeJournalEntry(
    accountingPeriod: Instant,
    debit: JournalEntry.Account,
    credit: JournalEntry.Account,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
    occurredAt: Instant,
    event: JournalEntry.Event,
    chargeId: Option[String] = None,
    balanceTransactionId: Option[String] = None,
    disputeId: Option[String] = None,
    refundId: Option[String] = None,
    customerBalanceTransactionId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    paymentRecordId: Option[String] = None,
    creditNoteId: Option[String] = None,
    creditNoteLineItemId: Option[String] = None,
    creditBalanceTransactionId: Option[String] = None,
  ): JournalEntry = {
    JournalEntry(
      accountingPeriod = accountingPeriod,
      attributionPeriod = None,
      debit = debit,
      credit = credit,
      settlementAmount = settlementAmount,
      settlementCurrency = settlementCurrency,
      presentmentAmount = presentmentAmount,
      presentmentCurrency = presentmentCurrency,
      occurredAt = occurredAt,
      event = event,
      reversedEvent = None,
      principleAccount = principleAccount,
      stripeAccountId = transaction.stripeAccountId,
      liveMode = transaction.liveMode,
      revRecTransactionId = transaction.id,
      revRecTransactionType = transaction.tpe,
      customerId = Some(invoice.base.customerId),
      invoiceId = Some(invoice.base.id),
      invoiceLineItemId = Some(invoiceLineItem.base.id),
      invoiceItemId = invoiceLineItem.invoiceItem.map(_.base.id),
      chargeId = chargeId,
      balanceTransactionId = balanceTransactionId,
      disputeId = disputeId,
      refundId = refundId,
      customerBalanceTransactionId = customerBalanceTransactionId,
      paymentIntentId = paymentIntentId,
      paymentRecordId = paymentRecordId,
      subscriptionId = invoiceLineItem.subscriptionItem.map(_.subscriptionId),
      subscriptionItemId = invoiceLineItem.base.subscriptionItemId,
      creditBalanceTransactionId = creditBalanceTransactionId,
      creditNoteId = creditNoteId,
      creditNoteLineItemId = creditNoteLineItemId,
      productId = invoiceLineItem.price.map(_.base.productId),
      priceId = invoiceLineItem.base.priceId,
      createdAt = syncedAt
    )
  }

  private[this] def bookBillingEvents(invoicingEntries: Seq[JournalEntry]): Seq[JournalEntry] = {
    val isNegative = settlementAmount.value < 0
    val billedAmount = sumAccount(invoicingEntries, JournalEntry.Account.AccountsReceivable).getOrElse(JournalEntryAmount.empty(settlementAmount.currency, invoiceLineItem.base.currency))
    val journalEntryEvents = {
      val entries = ProcessBillingEvent.buildJournalEntryEvents(
        billedAmount = if (isNegative) -billedAmount else billedAmount,
        events = billingEvents
          .map {
            case e: MoneyMovementBillingEvent =>
              if (isNegative) {
                e.copy(
                  amount = -e.amount,
                  amountBreakdown = e.amountBreakdown.map(-_)
                )
              } else {
                e
              }
            case e: PrepaidCreditNoteIssuedBillingEvent =>
              if (isNegative) {
                e.copy(totalAmount = -e.totalAmount, amountBreakdown = e.amountBreakdown.map(-_))
              } else {
                e
              }
            case e: PayInvoiceBillingEvent => if (isNegative) { e.copy(amount = -e.amount) } else { e }
            case e: PrepaidCreditNoteVoidedBillingEvent => e
            case e: MarkPaidBillingEvent => e
            case e: MarkUncollectibleBillingEvent => e
            case e: VoidBillingEvent => e
          }
          .sorted
      )

      // TODO: we should just support the negative number naturally instead of doing this odd flipping thing.
      if (isNegative) {
        entries.map {
          case e: PaymentJournalEntryEvent => e.copy(amount = -e.amount)
          case e: ReturnedPaymentJournalEntryEvent => e.copy(amount = -e.amount)
          case e: ContraJournalEntryEvent => e.copy(amount = -e.amount, paidAmount = -e.paidAmount)
          case e: FxLossJournalEntryEvent => e.copy(amount = -e.amount)
          case e: PayInvoiceJournalEntryEvent => e.copy(amount = -e.amount)
          case e: MarkPaidJournalEntryEvent => e
          case e: RecoverableJournalEntryEvent => e.copy(amount = -e.amount)
          case e: MarkUncollectibleJournalEntryEvent => e
          case e: VoidJournalEntryEvent => e
          case e: CreditNoteVoidedJournalEntryEvent => e
        }
      } else {
        entries
      }
    }

    val totalCreditGrantAmount = invoiceLineItem.creditGrants.map(_.base.amount).sum
    ProcessBillingEvent.buildJournalEntries(
      settlementCurrency = settlementAmount.currency,
      presentmentCurrency = invoiceLineItem.base.currency,
      invoicingEntries = invoicingEntries,
      events = journalEntryEvents,
    )
  }

  private[this] def bookArAndPrinciple(): Seq[JournalEntry] = {
    if (principleAccount == JournalEntry.Account.Revenue) {
      // When booking revenue, there's DeferredRevenue and UnbilledDeferredRevenue involved.
      // Therefore, it has to be handled specially.
      if (
        invoiceLineItem.price.exists(_.base.recurringUsageType.contains("metered")) &&
          // a usage-based subscription item with a flat fee would generate 2 invoice line items that have the same Price ID.
          // `pricingUnitAmountDecimal` is null when the line item is the flat fee of the usage-based subscription item.
          // `pricingUnitAmountDecimal` is not null when the line item is the usage fee of the usage-based subscription item.
          invoiceLineItem.base.pricingUnitAmountDecimal.isDefined
      ) {
        bookArAndRevenueForUsageBased()
      } else {
        bookArAndRevenueForAmortizationBased()
      }
    } else {
      // The principle account might be TaxLiability, CustomerBalance, or other non-revenue accounts.
      bookArAndNonRevenue()
    }
  }

  private[this] def bookArAndNonRevenue(): Seq[JournalEntry] = {
    Seq(makeJournalEntry(
      accountingPeriod = getAccountingPeriod(invoice.base.finalizedAt.get),
      debit = JournalEntry.Account.AccountsReceivable,
      credit = principleAccount,
      settlementAmount = settlementAmount.value,
      settlementCurrency = settlementAmount.currency,
      presentmentAmount = presentmentAmount.value,
      presentmentCurrency = presentmentAmount.currency,
      occurredAt = invoice.base.finalizedAt.get,
      event = JournalEntry.Event.FinalizeInvoice,
    ))
  }

  private[this] def bookArAndRevenueForUsageBased(): Seq[JournalEntry] = {
    val invoicedPeriod = getAccountingPeriod(invoice.base.finalizedAt.get)

    val (unbilledEntriesBefore, _) = ProcessUnbilledUsageSubscriptionItem
      .generateUsageBasedJournalEntries(
        startedAt = invoiceLineItem.base.startedAt.get,
        endedAt = invoiceLineItem.base.endedAt.get,
        price = invoiceLineItem.price.get,
        meterEventSummaries = invoiceLineItem.meterEventSummaries,
        exchangeRate = invoiceLineItem.startedAtExchangeRate,
        discounts = Seq.empty,
        taxRates = Seq.empty,
        invoiceLineItemTotalDiscountAmount = invoiceLineItem.totalDiscountAmount,
        invoiceLineItemTotalCreditGrantAmount = invoiceLineItem.totalPaidCreditGrantedAmount + invoiceLineItem.totalPromotionalCreditGrantedAmount,
        invoiceLineItemTotalInclusiveTaxAmount = invoiceLineItem.totalInclusiveTaxAmount,
      )
      .partition(_.accountingPeriod.isBefore(invoicedPeriod))

    val unbilledRevenue = sumAccount(unbilledEntriesBefore, JournalEntry.Account.Revenue)
      .getOrElse(JournalEntryAmount(Amount(0L, settlementAmount.currency),Amount(0, invoiceLineItem.base.currency)))

    val creditGrantSettlementAmounts = if (invoiceLineItem.creditGrants.nonEmpty) {
      amortize(
        invoice.finalizedAtExchangeRate.get.exchange(invoiceLineItem.totalPaidCreditGrantedAmount + invoiceLineItem.totalPromotionalCreditGrantedAmount),
        invoiceLineItem.creditGrants.map(_.base.amount)
      )
    } else {
      Seq.empty
    }

    val creditGrantEntries = creditGrantSettlementAmounts.zip(invoiceLineItem.creditGrants).map { case (settlementCreditGrantAmount, creditGrant) =>
      makeJournalEntry(
        accountingPeriod = invoicedPeriod,
        debit = if (creditGrant.creditBalanceTransaction.exists(_.creditGrant.exists(_.category == "paid"))) {
          JournalEntry.Account.PaidCreditGrants
        } else {
          JournalEntry.Account.PromotionalCreditGrants
        },
        credit = JournalEntry.Account.AccountsReceivable,
        settlementAmount = settlementCreditGrantAmount,
        settlementCurrency = settlementAmount.currency,
        presentmentAmount = creditGrant.base.amount,
        presentmentCurrency = invoiceLineItem.base.currency,
        occurredAt = invoice.base.finalizedAt.get,
        event = JournalEntry.Event.FinalizeInvoice,
      )
    }

    val unrecognizedRevenue = JournalEntryAmount(
      settlement = Amount(settlementAmount.value, settlementAmount.currency),
      presentment = Amount(presentmentAmount.value, presentmentAmount.currency)
    ) - unbilledRevenue

    // Reclassify the previously-unbilled AR (at the start-of-period rate) to billed AR.
    val reclassifyUnbilledAr = makeJournalEntry(
      accountingPeriod = invoicedPeriod,
      debit = JournalEntry.Account.AccountsReceivable,
      credit = JournalEntry.Account.UnbilledAccountsReceivable,
      settlementAmount = unbilledRevenue.settlement.value,
      settlementCurrency = settlementAmount.currency,
      presentmentAmount = unbilledRevenue.presentment.value,
      presentmentCurrency = invoiceLineItem.base.currency,
      occurredAt = invoice.base.finalizedAt.get,
      event = JournalEntry.Event.FinalizeInvoice,
    )

    val currentPeriodEntries = Seq(
      makeJournalEntry(
        accountingPeriod = invoicedPeriod,
        debit = JournalEntry.Account.AccountsReceivable,
        credit = JournalEntry.Account.Revenue,
        settlementAmount = unrecognizedRevenue.settlement.value,
        settlementCurrency = settlementAmount.currency,
        presentmentAmount = unrecognizedRevenue.presentment.value,
        presentmentCurrency = invoiceLineItem.base.currency,
        occurredAt = invoice.base.finalizedAt.get,
        event = JournalEntry.Event.FinalizeInvoice,
      ),
    )

    unbilledEntriesBefore ++ Seq(reclassifyUnbilledAr) ++ currentPeriodEntries ++ creditGrantEntries
  }

  private[this] def bookArAndRevenueForAmortizationBased(): Seq[JournalEntry] = {
    val startedAt = invoiceLineItem.base.startedAt
      .orElse(invoiceLineItem.invoiceItem.flatMap(_.base.startedAt))
      .orElse(invoiceLineItem.invoiceItem.map(_.base.createdAt))
      .getOrElse(invoice.base.finalizedAt.get)
    val endedAt = Instant.max(
      invoiceLineItem.base.endedAt
        .orElse(invoiceLineItem.invoiceItem.flatMap(_.base.endedAt))
        .orElse(invoiceLineItem.invoiceItem.map(_.base.createdAt))
        .getOrElse(invoice.base.finalizedAt.get),
      startedAt
    )

    val presentmentPeriods = amortize(presentmentAmount.value, startedAt, endedAt)
    val settlementPeriods = amortize(settlementAmount.value, startedAt, endedAt)

    val invoicedPeriod = getAccountingPeriod(invoice.base.finalizedAt.get)
    val (_, presentmentPeriodsAfter) = presentmentPeriods.partition(_.startedAt.isBefore(invoicedPeriod))
    val (_, settlementPeriodsAfter) = settlementPeriods.partition(_.startedAt.isBefore(invoicedPeriod))

    val (unbilledFromInvoiceLineItemBefore, _) = ProcessUnbilledInvoiceItem
      .generateJournalEntries(
        startedAt = startedAt,
        endedAt = endedAt,
        amount = JournalEntryAmount(
          settlement = Amount(settlementAmount.value, settlementAmount.currency),
          presentment = Amount(presentmentAmount.value, invoiceLineItem.base.currency)
        ),
        occurredAt = invoice.base.finalizedAt.get,
      )
      .partition(_.accountingPeriod.isBefore(invoicedPeriod))
    val unbilledAccountsReceivableAmountFromInvoiceLineItem = sumAccount(unbilledFromInvoiceLineItemBefore, JournalEntry.Account.UnbilledAccountsReceivable)
    val unbilledRevenueFromInvoiceLineItem = sumAccount(unbilledFromInvoiceLineItemBefore, JournalEntry.Account.Revenue)

    val (unbilledEntriesFromInvoiceItemBefore, _) = {
      val entries = if (invoiceLineItem.price.exists(_.base.recurringUsageType.contains("metered"))) {
        ProcessUnbilledInvoiceItem.generateJournalEntries(
          startedAt = startedAt,
          endedAt = endedAt,
          amount = JournalEntryAmount(
            settlement = Amount(invoiceLineItem.startedAtExchangeRate.exchange(presentmentAmount.value), invoiceLineItem.startedAtExchangeRate.exchangeCurrency),
            presentment = Amount(presentmentAmount.value, invoiceLineItem.base.currency),
          ),
          occurredAt = startedAt
        )
      } else {
        invoiceLineItem.invoiceItem
          .map { invoiceItem => ProcessUnbilledInvoiceItem(transaction, invoiceItem).generateRawJournalEntries() }
          .getOrElse(Seq.empty)
      }

      entries.partition(_.accountingPeriod.isBefore(invoicedPeriod))
    }
    val unbilledAccountsReceivableAmountFromInvoiceItem = sumAccount(unbilledEntriesFromInvoiceItemBefore, JournalEntry.Account.UnbilledAccountsReceivable)
    val unbilledRevenueFromInvoiceItem = sumAccount(unbilledEntriesFromInvoiceItemBefore, JournalEntry.Account.Revenue)

    val bookDrEntries = if (unbilledFromInvoiceLineItemBefore.nonEmpty) {
      Seq(
        makeJournalEntry(
          accountingPeriod = invoicedPeriod,
          debit = JournalEntry.Account.UnbilledDeferredRevenue,
          credit = JournalEntry.Account.DeferredRevenue,
          settlementAmount = settlementPeriodsAfter.map(_.amount).sum,
          settlementCurrency = settlementAmount.currency,
          presentmentAmount = presentmentPeriodsAfter.map(_.amount).sum,
          presentmentCurrency = invoiceLineItem.base.currency,
          occurredAt = invoice.base.finalizedAt.get,
          event = JournalEntry.Event.FinalizeInvoice,
        ),
        makeJournalEntry(
          accountingPeriod = invoicedPeriod,
          debit = JournalEntry.Account.AccountsReceivable,
          credit = JournalEntry.Account.UnbilledAccountsReceivable,
          settlementAmount = settlementPeriods.map(_.amount).sum,
          settlementCurrency = settlementAmount.currency,
          presentmentAmount = presentmentPeriods.map(_.amount).sum,
          presentmentCurrency = invoiceLineItem.base.currency,
          occurredAt = invoice.base.finalizedAt.get,
          event = JournalEntry.Event.FinalizeInvoice,
        )
      )
    } else {
      Seq(makeJournalEntry(
        accountingPeriod = invoicedPeriod,
        debit = JournalEntry.Account.AccountsReceivable,
        credit = JournalEntry.Account.DeferredRevenue,
        settlementAmount = settlementAmount.value,
        settlementCurrency = settlementAmount.currency,
        presentmentAmount = presentmentAmount.value,
        presentmentCurrency = presentmentAmount.currency,
        occurredAt = invoice.base.finalizedAt.get,
        event = JournalEntry.Event.FinalizeInvoice,
      ))
    }

    val bookUnbilledEntries = if (unbilledEntriesFromInvoiceItemBefore.nonEmpty) {
      unbilledEntriesFromInvoiceItemBefore
    } else if (unbilledFromInvoiceLineItemBefore.nonEmpty) {
      unbilledFromInvoiceLineItemBefore
    } else {
      Seq.empty
    }

    val correctingUnbilledEntriesFromInvoiceItem = if (unbilledEntriesFromInvoiceItemBefore.nonEmpty) {
      Seq(
        makeJournalEntry(
          accountingPeriod = invoicedPeriod,
          debit = JournalEntry.Account.UnbilledDeferredRevenue,
          credit = JournalEntry.Account.UnbilledAccountsReceivable,
          settlementAmount = (
            unbilledAccountsReceivableAmountFromInvoiceItem.map(_.settlement.value).getOrElse(0L) -
              unbilledAccountsReceivableAmountFromInvoiceLineItem.map(_.settlement.value).getOrElse(0L)
            ),
          settlementCurrency = settlementAmount.currency,
          presentmentAmount = (
            unbilledAccountsReceivableAmountFromInvoiceItem.map(_.presentment.value).getOrElse(0L) -
              unbilledAccountsReceivableAmountFromInvoiceLineItem.map(_.presentment.value).getOrElse(0L)
            ),
          presentmentCurrency = invoiceLineItem.base.currency,
          occurredAt = invoice.base.finalizedAt.get,
          event = JournalEntry.Event.FinalizeInvoice,
        ),
        makeJournalEntry(
          accountingPeriod = invoicedPeriod,
          debit = JournalEntry.Account.Revenue,
          credit = JournalEntry.Account.UnbilledDeferredRevenue,
          settlementAmount = (
            unbilledRevenueFromInvoiceItem.map(_.settlement.value).getOrElse(0L) -
              unbilledRevenueFromInvoiceLineItem.map(_.settlement.value).getOrElse(0L)
            ),
          settlementCurrency = settlementAmount.currency,
          presentmentAmount = (
            unbilledRevenueFromInvoiceItem.map(_.presentment.value).getOrElse(0L) -
              unbilledRevenueFromInvoiceLineItem.map(_.presentment.value).getOrElse(0L)
            ),
          presentmentCurrency = invoiceLineItem.base.currency,
          occurredAt = invoice.base.finalizedAt.get,
          event = JournalEntry.Event.FinalizeInvoice,
        ),
      )
    } else {
      Seq.empty
    }

    val recognizedEntries = presentmentPeriodsAfter.zip(settlementPeriodsAfter).flatMap { case (presentmentPeriod, settlementPeriod) =>
      Seq(makeJournalEntry(
        accountingPeriod = settlementPeriod.startedAt,
        debit = JournalEntry.Account.DeferredRevenue,
        credit = JournalEntry.Account.Revenue,
        settlementAmount = settlementPeriod.amount,
        settlementCurrency = settlementAmount.currency,
        presentmentAmount = presentmentPeriod.amount,
        presentmentCurrency = invoiceLineItem.base.currency,
        occurredAt = invoiceLineItem.invoiceItem.map(_.base.createdAt).getOrElse(invoice.base.finalizedAt.get),
        event = JournalEntry.Event.RecognizeRevenue,
      ))
    }

    bookUnbilledEntries ++ correctingUnbilledEntriesFromInvoiceItem ++ bookDrEntries ++ recognizedEntries
  }

  private[this] def bookCustomerBalance(): Seq[JournalEntry] = {
    val appliedEntries = appliedCustomerBalance.map { cb =>
      makeJournalEntry(
        accountingPeriod = getAccountingPeriod(invoice.base.finalizedAt.get),
        debit = JournalEntry.Account.CustomerBalance,
        credit = JournalEntry.Account.AccountsReceivable,
        settlementAmount = invoice.finalizedAtExchangeRate.get.exchange(cb.amount.settlement.value),
        settlementCurrency = invoice.finalizedAtExchangeRate.get.exchangeCurrency,
        presentmentAmount = cb.amount.presentment.value,
        presentmentCurrency = cb.amount.presentment.currency,
        occurredAt = invoice.base.finalizedAt.get,
        event = JournalEntry.Event.FinalizeInvoice,
        customerBalanceTransactionId = cb.customerBalanceTransaction.map(_.id),
      )
    }

    val unappliedEntries = invoice.base.voidedAt.toList.flatMap { voidedAt =>
      unappliedCustomerBalances.map { cb =>
        makeJournalEntry(
          accountingPeriod = getAccountingPeriod(invoice.base.finalizedAt.get),
          debit = JournalEntry.Account.CustomerBalance,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = invoice.finalizedAtExchangeRate.get.exchange(cb.amount.settlement.value),
          settlementCurrency = invoice.finalizedAtExchangeRate.get.exchangeCurrency,
          presentmentAmount = cb.amount.presentment.value,
          presentmentCurrency = cb.amount.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidInvoice,
          customerBalanceTransactionId = cb.customerBalanceTransaction.map(_.id),
        )
      }
    }

    appliedEntries.toList ++ unappliedEntries
  }

  private[this] def bookCreditBalanceAppliedOnVoid(): Seq[JournalEntry] = {
    invoice.base.voidedAt.toList.flatMap { voidedAt =>
      creditBalanceTransactionOnVoids.map { cbt =>
        makeJournalEntry(
          accountingPeriod = getAccountingPeriod(voidedAt),
          debit = JournalEntry.Account.AccountsReceivable,
          credit = cbt.creditBalanceTransaction.creditGrant.get.category match {
            case "paid" => JournalEntry.Account.PaidCreditGrants
            case "promotional" => JournalEntry.Account.PromotionalCreditGrants
          },
          settlementAmount = cbt.amount.settlement.value,
          settlementCurrency = cbt.amount.settlement.currency,
          presentmentAmount = cbt.amount.presentment.value,
          presentmentCurrency = cbt.amount.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidInvoice,
          creditBalanceTransactionId = Some(cbt.creditBalanceTransaction.base.id)
        )
      }
    }
  }
}

case class ProcessInvoiceLineItem(
  transaction: RevRecTransaction,
  invoice: RichInvoice,
  invoiceLineItem: RichInvoiceLineItem,
  settlementAmount: Amount,
  appliedCustomerBalances: Seq[ProcessCustomerBalance],
  unappliedCustomerBalances: Seq[ProcessCustomerBalance],
  billingEvents: Seq[BillingEvent],
  syncedAt: Instant
) {
  private[this] val logger = Logger(getClass)

  def generateJournalEntries(): Seq[JournalEntry] = {
    val revTaxWeights = Seq(invoiceLineItem.totalPrincipleAmount, invoiceLineItem.totalTaxAmount)
    val Seq(settlementPrinciple, settlementTax) = amortize(settlementAmount.value, revTaxWeights)

    val arPrincipleTaxWeights = Seq(invoiceLineItem.totalPrincipleAfterCreditGrants, invoiceLineItem.totalTaxAmount)
    val Seq(principleAppliedCustomerBalances, taxAppliedCustomerBalances) = if (appliedCustomerBalances.nonEmpty) {
      appliedCustomerBalances.map { a =>
        amortize4(a.amount, arPrincipleTaxWeights).map { amount => a.copy(amount = amount) }
      }.transpose
    } else {
      Seq(Seq.empty, Seq.empty)
    }
    val Seq(principleUnappliedCustomerBalances, taxUnappliedCustomerBalances) = if (unappliedCustomerBalances.nonEmpty) {
      unappliedCustomerBalances.map { a =>
        amortize4(a.amount, arPrincipleTaxWeights).map { amount => a.copy(amount = amount) }
      }.transpose
    } else {
      Seq(Seq.empty, Seq.empty)
    }
    val principleCreditBalanceTransactionOnVoids = invoiceLineItem.creditBalanceTransactionsAppliedOnVoid.map { a =>
      ProcessCreditBalanceTransactionOnVoid(
        amount = JournalEntryAmount(
          settlement = Amount(invoice.finalizedAtExchangeRate.get.exchange(a.base.creditAmount.get), settlementAmount.currency),
          presentment = Amount(a.base.creditAmount.get, invoiceLineItem.base.currency)
        ),
        creditBalanceTransaction = a
      )
    }

    val Seq(principleBillingEvents, taxBillingEvents) = if (billingEvents.nonEmpty) {
      billingEvents
        .map {
          case e: MoneyMovementBillingEvent =>
            val Seq(rev, tax) = e.amountBreakdown.map { a =>
              if (e.amount.settlement.value < 0) {
                Seq(-a.revenue, -a.tax)
              } else {
                Seq(a.revenue, a.tax)
              }
            }.getOrElse { amortize3(e.amount.toDualAmount(), arPrincipleTaxWeights) }
            Seq(
              e.copy(
                amount = JournalEntryAmount(
                  settlement = Amount(rev.settlement, e.amount.settlement.currency),
                  presentment = Amount(rev.presentment, e.amount.presentment.currency)
                ),
                amountBreakdown = None
              ),
              e.copy(
                amount = JournalEntryAmount(
                  settlement = Amount(tax.settlement, e.amount.settlement.currency),
                  presentment = Amount(tax.presentment, e.amount.presentment.currency)
                ),
                amountBreakdown = None,
                contraAccount = Some(JournalEntry.Account.TaxLiability)
              ),
            )
          case e: PayInvoiceBillingEvent =>
            val Seq(rev, tax) = amortize4(e.amount, arPrincipleTaxWeights)
            Seq(e.copy(amount = rev), e.copy(amount = tax))
          case e: MarkUncollectibleBillingEvent => Seq(e, e.copy(principleAccount = JournalEntry.Account.TaxLiability))
          case e: VoidBillingEvent => Seq(e, e.copy(principleAccount = JournalEntry.Account.TaxLiability))
          case e: MarkPaidBillingEvent => Seq(e, e)
          case e: PrepaidCreditNoteIssuedBillingEvent =>
            val Seq(rev, tax) = e.amountBreakdown.map { a =>
              if (e.totalAmount.settlement.value < 0) {
                Seq(-a.revenue, -a.tax)
              } else {
                Seq(a.revenue, a.tax)
              }
            }.getOrElse { amortize3(e.totalAmount.toDualAmount(), arPrincipleTaxWeights) }

            Seq(
              e.copy(
                totalAmount = e.totalAmount.copy(
                  settlement = e.totalAmount.settlement.copy(value = rev.settlement),
                  presentment = e.totalAmount.presentment.copy(value = rev.presentment)
                ),
                amountBreakdown = None
              ),
              e.copy(
                totalAmount = e.totalAmount.copy(
                  settlement = e.totalAmount.settlement.copy(value = tax.settlement),
                  presentment = e.totalAmount.presentment.copy(value = tax.presentment)
                ),
                amountBreakdown = None,
                principleAccount = JournalEntry.Account.TaxLiability,
              ),
            )
          case e: PrepaidCreditNoteVoidedBillingEvent => Seq(e, e.copy(principleAccount = JournalEntry.Account.TaxLiability))
        }
        .transpose
        .toList
    } else {
      Seq(Seq.empty, Seq.empty)
    }

    val result = Seq(
      ProcessInvoiceLineItemComponent(
        transaction = transaction,
        invoice = invoice,
        invoiceLineItem = invoiceLineItem,
        principleAccount = JournalEntry.Account.Revenue,
        settlementAmount = Amount(settlementPrinciple, settlementAmount.currency),
        presentmentAmount = Amount(invoiceLineItem.totalPrincipleAmount, invoiceLineItem.base.currency),
        appliedCustomerBalance = principleAppliedCustomerBalances,
        unappliedCustomerBalances = principleUnappliedCustomerBalances,
        creditBalanceTransactionOnVoids = principleCreditBalanceTransactionOnVoids,
        billingEvents = principleBillingEvents,
        syncedAt = syncedAt
      ),
      ProcessInvoiceLineItemComponent(
        transaction = transaction,
        invoice = invoice,
        invoiceLineItem = invoiceLineItem,
        principleAccount = JournalEntry.Account.TaxLiability,
        settlementAmount = Amount(settlementTax, settlementAmount.currency),
        presentmentAmount = Amount(invoiceLineItem.totalTaxAmount, invoiceLineItem.base.currency),
        appliedCustomerBalance = taxAppliedCustomerBalances,
        unappliedCustomerBalances = taxUnappliedCustomerBalances,
        creditBalanceTransactionOnVoids = Seq.empty,
        billingEvents = taxBillingEvents,
        syncedAt = syncedAt
      ),
    )
      .flatMap(_.generateJournalEntries())
    result
  }
}

object ProcessInvoice {
  def selectInvoiceFinalizedAtExchangeRate(
    invoice: RichInvoice,
    exchangeRateService: ExchangeRateService,
    defaultSettlementCurrency: String,
  ): ExchangeRate = {
    val charges = (invoice.payments.flatMap(_.charge) ++ invoice.payments.flatMap(_.paymentIntent.flatMap(_.charge)).filter(_.balanceTransaction.isDefined))
    val selectedCharge = charges.sortBy(_.balanceTransaction.get.createdAt).headOption

    val settlementCurrency = selectedCharge.map(_.balanceTransaction.get.currency).getOrElse(defaultSettlementCurrency)

    invoice.base.finalizedAt
      .map { finalizedAt =>
        await(exchangeRateService.get(selectedCharge.flatMap(_.base.balanceTransactionId), invoice.base.currency, settlementCurrency, finalizedAt))
      }
      .getOrElse(ExchangeRate.sameCurrency(invoice.base.currency))
  }
}

case class ProcessInvoice(
  transaction: RevRecTransaction,
  invoice: RichInvoice,
) extends ProcessRevRecTransaction {
  lazy val syncedAt: Instant = invoice.syncedAt
  lazy val startedAt: Option[Instant] = invoice.base.finalizedAt
  lazy val status: RevRecTransaction.Status = invoice.base.status match {
    case "draft" => Status.Draft
    case "open" => Status.Open
    case "paid" => Status.Paid
    case "uncollectible" => Status.Uncollectible
    case "void" => Status.Voided
    case other => throw new RuntimeException(s"Unknown status: $other")
  }

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    if (invoice.base.finalizedAt.isEmpty) {
      return Seq.empty
    }

    val invoiceLineItemTotalBeforeCreditGrants = invoice.lineItems.map(_.totalBeforeAppliedCreditGrants)
    val settlementAmounts = amortize(
      invoice.finalizedAtExchangeRate.get.exchange(invoiceLineItemTotalBeforeCreditGrants.sum),
      invoiceLineItemTotalBeforeCreditGrants
    )
    val invoiceLineItemTotals = invoice.lineItems.map(_.total)
    val appliedCustomerBalances = {
      invoice.customerBalanceTransactions.filter(_.`type` != "unapplied_from_invoice").map { cbt =>
        val appliedCustomerBalanceAmounts = amortize(cbt.amount, invoiceLineItemTotals)
        val settlementAppliedCustomerBalanceAmounts = amortize(invoice.finalizedAtExchangeRate.get.exchange(cbt.amount), invoiceLineItemTotals)

        appliedCustomerBalanceAmounts.zip(settlementAppliedCustomerBalanceAmounts).map { case (presentmentAmount, settlementAmount) =>
          ProcessCustomerBalance(
            amount = JournalEntryAmount(
              settlement = Amount(settlementAmount, invoice.base.currency), presentment = Amount(presentmentAmount, invoice.base.currency)
            ),
            customerBalanceTransaction = None
          )
        }
      }
        .transpose
    }
    val unappliedCustomerBalances = invoice.customerBalanceTransactions.filter(_.`type` == "unapplied_from_invoice").map { cbt =>
      val settlementAmounts = amortize(invoice.finalizedAtExchangeRate.get.exchange(cbt.amount), invoiceLineItemTotals)
      val presentmentAmounts = amortize(cbt.amount, invoiceLineItemTotals)

      invoice.lineItems.zip(settlementAmounts).zip(presentmentAmounts).map { case ((lineItem, settlementAmount), presentmentAmount) =>
        ProcessCustomerBalance(
          amount = JournalEntryAmount(
            settlement = Amount(settlementAmount, invoice.finalizedAtExchangeRate.get.exchangeCurrency), presentment = Amount(presentmentAmount, invoice.base.currency)
          ),
          customerBalanceTransaction = None
        )
      }
    }
      .transpose
      .toArray

    val (invoiceBillingEvents, lineItemBillingEvents) = {
      val groups = makeBillingEvents()
        .groupBy {
          case e: MoneyMovementBillingEvent => e.invoiceLineItemId
          case e: PrepaidCreditNoteIssuedBillingEvent => e.invoiceLineItemId
          case e: PrepaidCreditNoteVoidedBillingEvent => e.invoiceLineItemId
          case _ => None
        }
        .view
        .mapValues(_.toList)
        .toMap

      (
        groups.getOrElse(None, Seq.empty),
        groups
          .filter(_._1.isDefined)
          .map { case (invoiceLineItemId, events) => (invoiceLineItemId.get, events) }
          .toMap
      )
    }

    val invoiceLineItemRevenues = invoice.lineItems.map(_.totalPrincipleAfterCreditGrants)
    val invoiceLineItemTaxes = invoice.lineItems.map(_.totalTaxAmount)
    val billingEvents = invoiceBillingEvents
      .map {
        case e: MoneyMovementBillingEvent =>
          val settlementAmounts = amortize(e.amount.settlement.value, invoiceLineItemTotals).toArray
          val presentmentAmounts = amortize(e.amount.presentment.value, invoiceLineItemTotals).toArray

          val breakdown = e.amountBreakdown
            .map { a =>
              Seq(
                amortize3(a.revenue, invoiceLineItemRevenues),
                amortize3(a.tax, invoiceLineItemTaxes),
              )
                .transpose
                .map { lineItemAmounts =>
                  AmountBreakdown(
                    revenue = lineItemAmounts(0),
                    tax = lineItemAmounts(1),
                  )
                }
                .toArray
            }

          invoice.lineItems.zipWithIndex.map { case (lineItem, index) =>
            e.copy(
              amount = JournalEntryAmount(
                settlement = e.amount.settlement.copy(value = settlementAmounts(index)),
                presentment = e.amount.presentment.copy(value = presentmentAmounts(index))
              ),
              amountBreakdown = breakdown.map(_.apply(index))
            )
          }
        case e: PayInvoiceBillingEvent =>
          val settlementAmounts = amortize(e.amount.settlement.value, invoiceLineItemTotals).toArray
          val presentmentAmounts = amortize(e.amount.presentment.value, invoiceLineItemTotals).toArray
          invoice.lineItems.zipWithIndex.map { case (lineItem, index) =>
            e.copy(
              amount = JournalEntryAmount(
                settlement = e.amount.settlement.copy(value = settlementAmounts(index)),
                presentment = e.amount.presentment.copy(value = presentmentAmounts(index))
              ),
            )
          }
        case e: MarkUncollectibleBillingEvent => invoice.lineItems.map { _ => e }
        case e: VoidBillingEvent => invoice.lineItems.map { _ => e }
        case e: MarkPaidBillingEvent => invoice.lineItems.map { _ => e }
        case e: PrepaidCreditNoteIssuedBillingEvent =>
          val totalAmounts = amortize3(e.totalAmount.toDualAmount(), invoiceLineItemTotals)
          val revenues = e.amountBreakdown.map { a => amortize3(a.revenue, invoiceLineItemRevenues).map(Some.apply) }.getOrElse(invoice.lineItems.map { _ => None })
          val taxes = e.amountBreakdown.map { a => amortize3(a.tax, invoiceLineItemTaxes).map(Some.apply) }.getOrElse(invoice.lineItems.map { _ => None })
          val amountBreakdowns = revenues.zip(taxes).map { case (revenue, tax) =>
            for {
              r <- revenue
              t <- tax
            } yield {
              AmountBreakdown(revenue = r, tax = t)
            }
          }

          invoice.lineItems.zipWithIndex.map { case (lineItem, index) =>
            e.copy(
              totalAmount = JournalEntryAmount(
                settlement = Amount(totalAmounts(index).settlement, e.totalAmount.settlement.currency),
                presentment = Amount(totalAmounts(index).presentment, e.totalAmount.presentment.currency),
              ),
              amountBreakdown = amountBreakdowns(index)
            )
          }
        case e: PrepaidCreditNoteVoidedBillingEvent => invoice.lineItems.map { _ => e }
      }
      .transpose
      .toArray

    val processInvoiceLineItems = invoice.lineItems.zipWithIndex
      .map { case (lineItem, index) =>
        ProcessInvoiceLineItem(
          transaction = transaction,
          invoice = invoice,
          invoiceLineItem = lineItem,
          settlementAmount = Amount(settlementAmounts(index), invoice.finalizedAtExchangeRate.get.exchangeCurrency),
          appliedCustomerBalances = if (index < appliedCustomerBalances.length) appliedCustomerBalances(index) else Seq.empty,
          unappliedCustomerBalances = if (index < unappliedCustomerBalances.length) unappliedCustomerBalances.apply(index) else Seq.empty,
          billingEvents = {
            val items = if (index < billingEvents.length) billingEvents.apply(index) else Seq.empty
            val specifics = lineItemBillingEvents.getOrElse(lineItem.base.id, Seq.empty)

            (items ++ specifics).sorted
          },
          syncedAt = syncedAt
        )
      }

    Seq(
      bookFees(),
      processInvoiceLineItems.flatMap(_.generateJournalEntries())
    )
      .flatten
      .map { entry =>
        entry.copy(
          stripeAccountId = transaction.stripeAccountId,
          liveMode = transaction.liveMode,
          revRecTransactionId = transaction.id,
          revRecTransactionType = transaction.tpe,
          customerId = Some(invoice.base.customerId),
          invoiceId = Some(invoice.base.id),
          createdAt = syncedAt
        )
      }
      .sortBy { e => (e.accountingPeriod, e.occurredAt, e.debit, e.credit) }
  }

  private[this] def makeJournalEntry(
    accountingPeriod: Instant,
    debit: JournalEntry.Account,
    credit: JournalEntry.Account,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
    occurredAt: Instant,
    event: JournalEntry.Event,
    principleAccount: JournalEntry.Account,
    invoiceLineItemId: Option[String] = None,
    invoiceItemId: Option[String] = None,
    chargeId: Option[String] = None,
    balanceTransactionId: Option[String] = None,
    disputeId: Option[String] = None,
    refundId: Option[String] = None,
    customerBalanceTransactionId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    paymentRecordId: Option[String] = None
  ): JournalEntry = {
    JournalEntry(
      accountingPeriod = accountingPeriod,
      attributionPeriod = None,
      debit = debit,
      credit = credit,
      settlementAmount = settlementAmount,
      settlementCurrency = settlementCurrency,
      presentmentAmount = presentmentAmount,
      presentmentCurrency = presentmentCurrency,
      occurredAt = occurredAt,
      event = event,
      reversedEvent = None,
      principleAccount = principleAccount,
      stripeAccountId = transaction.stripeAccountId,
      liveMode = transaction.liveMode,
      revRecTransactionId = transaction.id,
      revRecTransactionType = transaction.tpe,
      customerId = Some(invoice.base.customerId),
      invoiceId = Some(invoice.base.id),
      invoiceLineItemId = invoiceLineItemId,
      invoiceItemId = invoiceItemId,
      chargeId = chargeId,
      balanceTransactionId = balanceTransactionId,
      disputeId = disputeId,
      refundId = refundId,
      customerBalanceTransactionId = customerBalanceTransactionId,
      paymentIntentId = paymentIntentId,
      paymentRecordId = paymentRecordId,
      subscriptionId = None,
      subscriptionItemId = None,
      creditBalanceTransactionId = None,
      creditNoteId = None,
      creditNoteLineItemId = None,
      productId = None,
      priceId = None,
      createdAt = syncedAt
    )
  }

  private[this] def bookFees(): Seq[JournalEntry] = {
    val fromCharges = for {
      p <- invoice.payments
      c <- Seq(p.charge, p.paymentIntent.flatMap(_.charge)).flatten
      bt <- c.balanceTransaction.toList
      if bt.feeAmount != 0
    } yield makeJournalEntry(
      accountingPeriod = getAccountingPeriod(bt.createdAt),
      debit = JournalEntry.Account.Fees,
      credit = JournalEntry.Account.Cash,
      settlementAmount = bt.feeAmount,
      settlementCurrency = bt.currency,
      presentmentAmount = bt.feeAmount,
      presentmentCurrency = bt.currency,
      occurredAt = bt.createdAt,
      event = JournalEntry.Event.PayFee,
      principleAccount = JournalEntry.Account.Fees,
      chargeId = Some(c.base.id),
      balanceTransactionId = Some(bt.id),
      paymentIntentId = c.base.paymentIntentId,
    )

    val fromRefunds = for {
      p <- invoice.payments
      c <- Seq(p.charge, p.paymentIntent.flatMap(_.charge)).flatten
      r <- c.refunds
      bt <- r.balanceTransaction.toList
      if bt.feeAmount != 0
    } yield makeJournalEntry(
      accountingPeriod = getAccountingPeriod(bt.createdAt),
      debit = JournalEntry.Account.Fees,
      credit = JournalEntry.Account.Cash,
      settlementAmount = bt.feeAmount,
      settlementCurrency = bt.currency,
      presentmentAmount = bt.feeAmount,
      presentmentCurrency = bt.currency,
      occurredAt = bt.createdAt,
      event = JournalEntry.Event.PayFee,
      principleAccount = JournalEntry.Account.Fees,
      chargeId = Some(c.base.id),
      balanceTransactionId = Some(bt.id),
      refundId = Some(r.base.id),
      paymentIntentId = c.base.paymentIntentId,
    )

    val fromDisputes = for {
      p <- invoice.payments
      c <- Seq(p.charge, p.paymentIntent.flatMap(_.charge)).flatten
      d <- c.disputes
      bt <- d.balanceTransactions.toList
      if bt.feeAmount != 0
    } yield makeJournalEntry(
      accountingPeriod = getAccountingPeriod(bt.createdAt),
      debit = JournalEntry.Account.Fees,
      credit = JournalEntry.Account.Cash,
      settlementAmount = bt.feeAmount,
      settlementCurrency = bt.currency,
      presentmentAmount = bt.feeAmount,
      presentmentCurrency = bt.currency,
      occurredAt = bt.createdAt,
      event = JournalEntry.Event.PayFee,
      principleAccount = JournalEntry.Account.Fees,
      chargeId = Some(c.base.id),
      balanceTransactionId = Some(bt.id),
      disputeId = Some(d.base.id),
      paymentIntentId = c.base.paymentIntentId,
    )

    fromCharges ++ fromRefunds ++ fromDisputes
  }

  private[this] def makeMoneyMovementEvents(
    charge: RichCharge,
    paymentIntentId: Option[String],
    payAndMoveMoney: Boolean
  ): Seq[ProcessBillingEvent.MoneyMovementBillingEvent] = {
    if (charge.balanceTransaction.isEmpty) { return Seq.empty }

    val paymentEvents = Seq(
      MoneyMovementBillingEvent(
        amount = JournalEntryAmount(
          settlement = Amount(charge.balanceTransaction.get.amount, charge.balanceTransaction.get.currency),
          presentment = Amount(charge.base.amount, charge.base.currency),
        ),
        amountBreakdown = None,
        journalEntryEvent = JournalEntry.Event.CreateCharge,
        assetAccount = JournalEntry.Account.Cash,
        contraAccount = None,
        occurredAt = charge.balanceTransaction.get.createdAt,
        payAndMoveMoney = payAndMoveMoney,
        chargeId = Some(charge.base.id),
        paymentIntentId = paymentIntentId,
        balanceTransactionId = Some(charge.balanceTransaction.get.id),
      )
    )

    val refundEvents = charge.refunds.filter(_.belongsToCreditNote == false).flatMap { refund =>
      Seq(
        Some(MoneyMovementBillingEvent(
          amount = JournalEntryAmount(
            settlement = Amount(refund.balanceTransaction.get.amount, refund.balanceTransaction.get.currency),
            presentment = Amount(-refund.base.amount, refund.base.currency),
          ),
          amountBreakdown = None,
          journalEntryEvent = JournalEntry.Event.RefundCharge,
          assetAccount = JournalEntry.Account.Cash,
          contraAccount = Some(JournalEntry.Account.Refunds),
          occurredAt = refund.balanceTransaction.get.createdAt,
          chargeId = Some(charge.base.id),
          paymentIntentId = paymentIntentId,
          refundId = Some(refund.base.id),
          balanceTransactionId = Some(refund.balanceTransaction.get.id),
        )),
        refund.failureBalanceTransaction.map { failureBt =>
          MoneyMovementBillingEvent(
            amount = JournalEntryAmount(
              settlement = Amount(failureBt.amount, failureBt.currency),
              presentment = Amount(refund.base.amount, refund.base.currency),
            ),
            amountBreakdown = None,
            journalEntryEvent = JournalEntry.Event.FailRefund,
            assetAccount = JournalEntry.Account.Cash,
            contraAccount = None,
            occurredAt = failureBt.createdAt,
            chargeId = Some(charge.base.id),
            paymentIntentId = paymentIntentId,
            refundId = Some(refund.base.id),
            balanceTransactionId = Some(failureBt.id),
          )
        }
      ).flatten
    }

    val disputeEvents = charge.disputes.flatMap { dispute =>
      dispute.balanceTransactions.map { bt =>
        MoneyMovementBillingEvent(
          amount = JournalEntryAmount(
            settlement = Amount(bt.amount, bt.currency),
            presentment = Amount(if (bt.amount >= 0) { dispute.base.amount } else { -dispute.base.amount }, dispute.base.currency),
          ),
          amountBreakdown = None,
          journalEntryEvent = if (bt.amount < 0) { JournalEntry.Event.DisputeCharge } else { JournalEntry.Event.WinDispute },
          assetAccount = JournalEntry.Account.Cash,
          contraAccount = Some(JournalEntry.Account.Disputes),
          occurredAt = bt.createdAt,
          chargeId = Some(charge.base.id),
          paymentIntentId = paymentIntentId,
          disputeId = Some(dispute.base.id),
          balanceTransactionId = Some(bt.id)
        )
      }
    }

    (paymentEvents ++ refundEvents ++ disputeEvents).sorted
  }

  private[this] def makeBillingEvents(): Seq[BillingEvent] = {
    val moneyMovementEvents: Seq[BillingEvent] = invoice.payments.flatMap { payment =>
      val bt = payment.charge.flatMap(_.balanceTransaction).orElse(payment.paymentIntent.flatMap(_.charge).flatMap(_.balanceTransaction))
      val shouldPayAndMoveMoneyAtTheSameTime = bt.exists { bt =>
        val paymentTime = payment.base.paidAt.getOrElse(bt.createdAt)
        val moneyMovementTime = bt.createdAt

        // The payment and money movement are in the same period and the invoice isn't marked as paid in-between (can happen in the test mode).
        getAccountingPeriod(bt.createdAt) == getAccountingPeriod(payment.base.paidAt.getOrElse(bt.createdAt)) &&
          invoice.base.paidAt.forall { invoicePaidAt => invoicePaidAt.toEpochMilli >= moneyMovementTime.toEpochMilli }
      }

      Seq(
        payment.base.paidAt.toList.flatMap { paidAt =>
          if (shouldPayAndMoveMoneyAtTheSameTime) {
            Seq.empty
          } else {
            Seq(PayInvoiceBillingEvent(
              amount = JournalEntryAmount(
                settlement = Amount(
                  value = invoice.finalizedAtExchangeRate.get.exchange(payment.base.amountPaid.get),
                  currency = invoice.finalizedAtExchangeRate.get.exchangeCurrency
                ),
                presentment = Amount(payment.base.amountPaid.get, payment.base.currency),
              ),
              occurredAt = paidAt,
              chargeId = payment.base.chargeId,
              paymentIntentId = payment.base.paymentIntentId,
              paymentRecordId = payment.base.paymentRecordId,
              invoiceLineItemId = None,
            ))
          }
        },
        payment.charge.toList.flatMap { charge => makeMoneyMovementEvents(charge, None, shouldPayAndMoveMoneyAtTheSameTime) },
        payment.paymentIntent.toList.flatMap { paymentIntent =>
          paymentIntent.charge.toList.flatMap { charge => makeMoneyMovementEvents(charge, Some(paymentIntent.base.id), shouldPayAndMoveMoneyAtTheSameTime) }
        },
        payment.base.paymentRecordId.toList.flatMap { paymentRecordId =>
          for {
            amountPaid <- payment.base.amountPaid
            paidAt <- payment.base.paidAt
          } yield {
            MoneyMovementBillingEvent(
              amount = JournalEntryAmount(
                settlement = Amount(invoice.finalizedAtExchangeRate.get.exchange(amountPaid), invoice.finalizedAtExchangeRate.get.exchangeCurrency),
                presentment = Amount(amountPaid, payment.base.currency),
              ),
              amountBreakdown = None,
              journalEntryEvent = JournalEntry.Event.CreateCharge,
              assetAccount = JournalEntry.Account.OutOfBandAssets,
              contraAccount = None,
              occurredAt = paidAt,
              payAndMoveMoney = shouldPayAndMoveMoneyAtTheSameTime,
              paymentRecordId = Some(paymentRecordId),
            )
          }
        }
      ).flatten
    }

    val prepaidCreditNoteEvents = invoice.creditNotes.flatMap { creditNote =>
      if (creditNote.lines.isEmpty) {
        Seq(
          Some(PrepaidCreditNoteIssuedBillingEvent(
            totalAmount = JournalEntryAmount(
              settlement = Amount(invoice.finalizedAtExchangeRate.get.exchange(creditNote.base.prePaymentAmount), invoice.finalizedAtExchangeRate.get.exchangeCurrency),
              presentment = Amount(creditNote.base.prePaymentAmount, creditNote.base.currency),
            ),
            amountBreakdown = None,
            occurredAt = creditNote.base.occurredAt,
            principleAccount = JournalEntry.Account.Revenue,
            creditNoteId = creditNote.base.id,
            creditNoteLineItemId = None,
            invoiceLineItemId = None
          )),
          creditNote.base.voidedAt.map { voidedAt =>
            PrepaidCreditNoteVoidedBillingEvent(
              occurredAt = voidedAt,
              principleAccount = JournalEntry.Account.Revenue,
              settlementCurrency = creditNote.base.currency,
              presentmentCurrency = invoice.finalizedAtExchangeRate.get.exchangeCurrency,
              creditNoteId = creditNote.base.id,
              creditNoteLineItemId = None,
              invoiceLineItemId = None
            )
          }
        ).flatten
      } else {
        val settlementAmounts = amortize(invoice.finalizedAtExchangeRate.get.exchange(creditNote.base.prePaymentAmount), creditNote.lines.map(_.total))
        val presentmentAmounts = amortize(creditNote.base.prePaymentAmount, creditNote.lines.map(_.total))
        creditNote.lines.zipWithIndex.flatMap { case (lineItem, index) =>
          val revTaxWeights = Seq(lineItem.totalPrincipleAfterCreditGrants, lineItem.totalTaxAmount)
          val Seq(settlementRevenue, settlementTax) = amortize(settlementAmounts(index), revTaxWeights)
          val Seq(presentmentRevenue, presentmentTax) = amortize(presentmentAmounts(index), revTaxWeights)
          val revAmount = DualAmount(settlementRevenue, presentmentRevenue)
          val taxAmount = DualAmount(settlementTax, presentmentTax)

          Seq(
            Some(PrepaidCreditNoteIssuedBillingEvent(
              totalAmount = JournalEntryAmount(
                settlement = Amount(revAmount.settlement + taxAmount.settlement, invoice.finalizedAtExchangeRate.get.exchangeCurrency),
                presentment = Amount(revAmount.presentment + taxAmount.presentment, creditNote.base.currency)
              ),
              amountBreakdown = Some(AmountBreakdown(revAmount, taxAmount)),
              principleAccount = JournalEntry.Account.Revenue,
              occurredAt = creditNote.base.occurredAt,
              creditNoteId = creditNote.base.id,
              creditNoteLineItemId = Some(lineItem.base.id),
              invoiceLineItemId = lineItem.base.invoiceLineItemId
            )),
            creditNote.base.voidedAt.map { voidedAt =>
              PrepaidCreditNoteVoidedBillingEvent(
                occurredAt = voidedAt,
                principleAccount = JournalEntry.Account.Revenue,
                settlementCurrency = creditNote.base.currency,
                presentmentCurrency = invoice.finalizedAtExchangeRate.get.exchangeCurrency,
                creditNoteId = creditNote.base.id,
                creditNoteLineItemId = Some(lineItem.base.id),
                invoiceLineItemId = lineItem.base.invoiceLineItemId
              )
            }
          ).flatten
        }
      }
    }

    case class AmortizedAmount(
      amount: JournalEntryAmount,
      refundId: Option[String],
      balanceTransactionId: Option[String],
      paymentRecordRefundId: Option[String],
      occurredAt: Instant
    )

    val postpaidCreditNoteEvents = invoice.creditNotes.flatMap { creditNote =>
      if (creditNote.lines.isEmpty) {
        val outOfBandEvent = if (creditNote.base.outOfBandAmount.getOrElse(0L) != 0) {
          Some(MoneyMovementBillingEvent(
            amount = JournalEntryAmount(
              Amount(-invoice.finalizedAtExchangeRate.get.exchange(creditNote.base.outOfBandAmount.getOrElse(0L)), invoice.finalizedAtExchangeRate.get.exchangeCurrency),
              Amount(-creditNote.base.outOfBandAmount.getOrElse(0L), invoice.base.currency)
            ),
            amountBreakdown = None,
            journalEntryEvent = JournalEntry.Event.IssueCreditNote,
            assetAccount = JournalEntry.Account.OutOfBandAssets,
            contraAccount = Some(JournalEntry.Account.CreditNotes),
            occurredAt = creditNote.base.occurredAt,
            creditNoteId = Some(creditNote.base.id),
            creditNoteLineItemId = None,
            invoiceLineItemId = None,
          ))
        } else {
          None
        }

        val customerBalanceEntry = if (creditNote.customerBalanceTransaction.map(_.amount).getOrElse(0L) != 0) {
          Some(MoneyMovementBillingEvent(
            amount = JournalEntryAmount(
              Amount(
                invoice.finalizedAtExchangeRate.get.exchange(creditNote.customerBalanceTransaction.map(_.amount).getOrElse(0L)),
                invoice.finalizedAtExchangeRate.get.exchangeCurrency
              ),
              Amount(creditNote.customerBalanceTransaction.map(_.amount).getOrElse(0L), invoice.base.currency)
            ),
            amountBreakdown = None,
            journalEntryEvent = JournalEntry.Event.IssueCreditNote,
            assetAccount = JournalEntry.Account.CustomerBalance,
            contraAccount = Some(JournalEntry.Account.CreditNotes),
            occurredAt = creditNote.customerBalanceTransaction.get.createdAt,
            creditNoteId = Some(creditNote.base.id),
            creditNoteLineItemId = None,
            invoiceLineItemId = None
          ))
        } else {
          None
        }

        val refundEntries = creditNote.refunds.map { refund =>
            MoneyMovementBillingEvent(
              amount = JournalEntryAmount(
                settlement = Amount(
                  value = refund.refund
                    .map { refund => refund.balanceTransaction.get.amount }
                    .getOrElse(-invoice.finalizedAtExchangeRate.get.exchange(refund.base.amountRefunded)),
                  currency = refund.refund.flatMap(_.balanceTransaction.map(_.currency)).getOrElse(invoice.finalizedAtExchangeRate.get.exchangeCurrency)
                ),
                presentment = Amount(
                  value = -refund.base.amountRefunded,
                  currency = creditNote.base.currency
                )
              ),
              amountBreakdown = None,
              journalEntryEvent = JournalEntry.Event.IssueCreditNote,
              assetAccount = if (refund.base.paymentRecordRefundId.isDefined) {
                JournalEntry.Account.OutOfBandAssets
              } else {
                JournalEntry.Account.Cash
              },
              contraAccount = Some(JournalEntry.Account.Refunds),
              occurredAt = refund.refund.map(_.balanceTransaction.get.createdAt).getOrElse(creditNote.base.occurredAt),
              creditNoteId = Some(creditNote.base.id),
              creditNoteLineItemId = None,
              invoiceLineItemId = None,
              refundId = refund.refund.map(_.base.id),
              paymentRecordId = refund.base.paymentRecordRefundId,
              balanceTransactionId = refund.refund.flatMap(_.base.balanceTransactionId),
            )
        }
        val refundFailureEntries = creditNote.refunds.flatMap { refund =>
          refund.refund.flatMap(_.failureBalanceTransaction).map { failureBt =>
            MoneyMovementBillingEvent(
              amount = JournalEntryAmount(
                settlement = Amount(
                  value = refund.refund
                    .map { refund => failureBt.amount }
                    .getOrElse(invoice.finalizedAtExchangeRate.get.exchange(refund.base.amountRefunded)),
                  currency = refund.refund.flatMap(_.balanceTransaction.map(_.currency)).getOrElse(invoice.finalizedAtExchangeRate.get.exchangeCurrency)
                ),
                presentment = Amount(
                  value = refund.base.amountRefunded,
                  currency = creditNote.base.currency
                )
              ),
              amountBreakdown = None,
              journalEntryEvent = JournalEntry.Event.IssueCreditNote,
              assetAccount = if (refund.base.paymentRecordRefundId.isDefined) {
                JournalEntry.Account.OutOfBandAssets
              } else {
                JournalEntry.Account.Cash
              },
              contraAccount = Some(JournalEntry.Account.Refunds),
              occurredAt = failureBt.createdAt,
              creditNoteId = Some(creditNote.base.id),
              creditNoteLineItemId = None,
              invoiceLineItemId = None,
              refundId = refund.refund.map(_.base.id),
              paymentRecordId = refund.base.paymentRecordRefundId,
              balanceTransactionId = Some(failureBt.id)
            )
          }
        }

        Seq(outOfBandEvent, customerBalanceEntry).flatten ++ refundEntries ++ refundFailureEntries
      } else {
        val weights = creditNote.lines.map(_.total)
        val outOfBandAmounts = amortize3(
          DualAmount(-invoice.finalizedAtExchangeRate.get.exchange(creditNote.base.outOfBandAmount.getOrElse(0L)), -creditNote.base.outOfBandAmount.getOrElse(0L)),
          weights
        ).toArray
        val customerBalanceAmounts = amortize3(
          DualAmount(invoice.finalizedAtExchangeRate.get.exchange(creditNote.customerBalanceTransaction.map(_.amount).getOrElse(0L)), creditNote.customerBalanceTransaction.map(_.amount).getOrElse(0L)),
          weights
        ).toArray
        val refunds = creditNote.refunds
          .map { refund =>
            val amounts = amortize3(
              DualAmount(
                settlement = refund.refund
                  .map { refund => refund.balanceTransaction.get.amount }
                  .getOrElse(-invoice.finalizedAtExchangeRate.get.exchange(refund.base.amountRefunded)),
                presentment = -refund.base.amountRefunded
              ),
              weights
            ).toArray
            val settlementCurrency = refund.refund
              .map { refund => refund.balanceTransaction.get.currency }
              .getOrElse(invoice.finalizedAtExchangeRate.get.exchangeCurrency)

            amounts.map { amount =>
              AmortizedAmount(
                amount = JournalEntryAmount(Amount(amount.settlement, settlementCurrency), Amount(amount.presentment, invoice.base.currency)),
                refundId = refund.refund.map(_.base.id),
                paymentRecordRefundId = refund.base.paymentRecordRefundId,
                balanceTransactionId = refund.refund.flatMap(_.balanceTransaction.map(_.id)),
                occurredAt = refund.refund.map(_.balanceTransaction.get.createdAt).getOrElse(creditNote.base.occurredAt)
              )
            }
          }
          .transpose
          .toArray
        val refundFailures = creditNote.refunds
          .map { refund =>
            refund.refund.flatMap(_.failureBalanceTransaction) match {
              case Some(failureBt) =>
                val amounts = amortize(refund.base.amountRefunded, weights)
                val settlementAmounts = amortize(failureBt.amount, weights)
                amounts.zip(settlementAmounts).map { case (amount, settlementAmount) =>
                  Some(AmortizedAmount(
                    amount = JournalEntryAmount(Amount(settlementAmount, failureBt.currency), Amount(amount, invoice.base.currency)),
                    refundId = refund.refund.map(_.base.id),
                    paymentRecordRefundId = refund.base.paymentRecordRefundId,
                    balanceTransactionId = Some(failureBt.id),
                    occurredAt = failureBt.createdAt
                  ))
                }
              case None => weights.map { _ => None }
            }
          }
          .transpose
          .toArray

        creditNote.lines.zipWithIndex.flatMap { case (lineItem, index) =>
          val paidPrincipleTaxWeights = Seq(lineItem.totalPrincipleAfterCreditGrants, lineItem.totalTaxAmount)
          val outOfBandAmount = outOfBandAmounts(index)
          val outOfBandEvent = if (!outOfBandAmount.isZero) {
            val Seq(rev, tax) = amortize3(outOfBandAmount, paidPrincipleTaxWeights)
            Some(MoneyMovementBillingEvent(
              amount = JournalEntryAmount(
                Amount(outOfBandAmount.settlement, invoice.finalizedAtExchangeRate.get.exchangeCurrency),
                Amount(outOfBandAmount.presentment, invoice.base.currency)
              ),
              amountBreakdown = Some(AmountBreakdown(-rev, -tax)),
              journalEntryEvent = JournalEntry.Event.IssueCreditNote,
              assetAccount = JournalEntry.Account.OutOfBandAssets,
              contraAccount = Some(JournalEntry.Account.CreditNotes),
              occurredAt = creditNote.base.occurredAt,
              creditNoteId = Some(creditNote.base.id),
              creditNoteLineItemId = Some(lineItem.base.id),
              invoiceLineItemId = lineItem.base.invoiceLineItemId,
            ))
          } else {
            None
          }

          val customerBalanceAmount = customerBalanceAmounts(index)
          val customerBalanceEntry = if (!customerBalanceAmount.isZero) {
            val Seq(rev, tax) = amortize3(customerBalanceAmount, paidPrincipleTaxWeights, outOfBandAmount)
            Some(MoneyMovementBillingEvent(
              amount = JournalEntryAmount(
                Amount(customerBalanceAmount.settlement, invoice.finalizedAtExchangeRate.get.exchangeCurrency),
                Amount(customerBalanceAmount.presentment, invoice.base.currency)
              ),
              amountBreakdown = Some(AmountBreakdown(-rev, -tax)),
              journalEntryEvent = JournalEntry.Event.IssueCreditNote,
              assetAccount = JournalEntry.Account.CustomerBalance,
              contraAccount = Some(JournalEntry.Account.CreditNotes),
              occurredAt = creditNote.base.occurredAt,
              creditNoteId = Some(creditNote.base.id),
              creditNoteLineItemId = Some(lineItem.base.id),
              invoiceLineItemId = lineItem.base.invoiceLineItemId,
            ))
          } else {
            None
          }

          val refundBaselines = scala.collection.mutable.Map.empty[String, DualAmount]
          var baseline = outOfBandAmount + customerBalanceAmount
          val refundEntries = if (index < refunds.length) {
            var cumulativeRefundAmount = DualAmount(0, 0)
            refunds(index).flatMap { refund =>
              if (!refund.amount.isZero) {
                val Seq(rev, tax) = amortize3(refund.amount.toDualAmount(), paidPrincipleTaxWeights, baseline)
                val _ = refundBaselines.put(refund.refundId.orElse(refund.paymentRecordRefundId).get, baseline)
                baseline += refund.amount.toDualAmount()
                Some(MoneyMovementBillingEvent(
                  amount = refund.amount,
                  amountBreakdown = Some(AmountBreakdown(-rev, -tax)),
                  journalEntryEvent = JournalEntry.Event.IssueCreditNote,
                  assetAccount = if (refund.paymentRecordRefundId.isDefined) {
                    JournalEntry.Account.OutOfBandAssets
                  } else {
                    JournalEntry.Account.Cash
                  },
                  contraAccount = Some(JournalEntry.Account.Refunds),
                  occurredAt = refund.occurredAt,
                  creditNoteId = Some(creditNote.base.id),
                  creditNoteLineItemId = Some(lineItem.base.id),
                  invoiceLineItemId = lineItem.base.invoiceLineItemId,
                  refundId = refund.refundId,
                  paymentRecordId = refund.paymentRecordRefundId,
                  balanceTransactionId = refund.balanceTransactionId
                ))
              } else {
                None
              }
            }
          } else {
            Seq.empty
          }

          val refundFailureEntries = if (index < refundFailures.length) {
            refundFailures(index).flatMap { refundFailure =>
              refundFailure.flatMap { refundFailure =>
                if (!refundFailure.amount.isZero) {
                  val Seq(rev, tax) = amortize3(
                    refundFailure.amount.toDualAmount(),
                    paidPrincipleTaxWeights,
                    refundBaselines(refundFailure.refundId.get)
                  )
                  Some(MoneyMovementBillingEvent(
                    amount = refundFailure.amount,
                    amountBreakdown = Some(AmountBreakdown(rev, tax)),
                    journalEntryEvent = JournalEntry.Event.IssueCreditNote,
                    assetAccount = if (refundFailure.paymentRecordRefundId.isDefined) {
                      JournalEntry.Account.OutOfBandAssets
                    } else {
                      JournalEntry.Account.Cash
                    },
                    contraAccount = Some(JournalEntry.Account.Refunds),
                    occurredAt = refundFailure.occurredAt,
                    creditNoteId = Some(creditNote.base.id),
                    creditNoteLineItemId = Some(lineItem.base.id),
                    invoiceLineItemId = lineItem.base.invoiceLineItemId,
                    refundId = refundFailure.refundId,
                    paymentRecordId = refundFailure.paymentRecordRefundId,
                    balanceTransactionId = refundFailure.balanceTransactionId
                  ))
                } else {
                  None
                }
              }
            }
          } else {
            Seq.empty
          }

          Seq(outOfBandEvent, customerBalanceEntry).flatten ++ refundEntries ++ refundFailureEntries
        }
      }
    }

    (
      moneyMovementEvents ++
        prepaidCreditNoteEvents ++
        postpaidCreditNoteEvents ++
        invoice.base.markedUncollectibleAt.map { timestamp =>
          MarkUncollectibleBillingEvent(timestamp, JournalEntry.Account.Revenue, invoice.finalizedAtExchangeRate.get.exchangeCurrency, invoice.base.currency)
        } ++
        invoice.base.voidedAt.map { timestamp =>
          VoidBillingEvent.apply(timestamp, JournalEntry.Account.Revenue, invoice.finalizedAtExchangeRate.get.exchangeCurrency, invoice.base.currency)
        } ++
        invoice.base.paidAt.map { timestamp =>
          MarkPaidBillingEvent.apply(timestamp, invoice.finalizedAtExchangeRate.get.exchangeCurrency, invoice.base.currency)
        }
    ).sorted
  }
}
