export const PreferredLangs = ['English', 'Thai', 'Japanese', 'German']
export type PreferredLang = typeof PreferredLangs[number]

export interface User {
  id: string
  email: string
  preferredLang: PreferredLang | null
  shouldReceiveNewsletter: boolean
  createdAt: number
}

export interface Customer {
  id: string,
  name: string | null,
  email: string | null
}

export interface RevRecTransaction {
  stripeAccountId: string
  liveMode: boolean
  id: string
  type: string
  customerId: string | null
  startedAt: number | null
  processedAt: number | null
  syncedAt: number | null
  title: string | null
  settlementTotalValue: number | null
  settlementCurrency: string | null
  customer: Customer | null
  invoice: Invoice | null
  charge: Charge | null
  paymentIntent: PaymentIntent | null
  invoiceItem: InvoiceItem | null
  subscriptionItem: SubscriptionItem | null
}

export type BillingActivity =
  FinalizeInvoice |
  MarkUncollectibleInvoice |
  VoidInvoice |
  CreditCustomerBalance |
  DebitCustomerBalance |
  CreditCreditBalance |
  DebitCreditBalance |
  MakePayment |
  IssueCreditNote |
  VoidCreditNote |
  IssueRefund |
  IssueOutOfBandRefund |
  FailRefund |
  FileDispute |
  WinDispute

export interface FileDispute {
  name: 'FileDispute'
  timestamp: number
  disputeId: string
  amount: number
  currency: string
}

export interface WinDispute {
  name: 'WinDispute'
  timestamp: number
  disputeId: string
  amount: number
  currency: string
}

export interface IssueCreditNote {
  name: 'IssueCreditNote'
  timestamp: number
  creditNoteId: string
  amount: number
  currency: string
}

export interface VoidCreditNote {
  name: 'VoidCreditNote'
  timestamp: number
  creditNoteId: string
  amount: number
  currency: string
}

export interface IssueRefund {
  name: 'IssueRefund'
  timestamp: number
  refundId: string
  amount: number
  currency: string
}

export interface IssueOutOfBandRefund {
  name: 'IssueOutOfBandRefund'
  timestamp: number
  amount: number
  currency: string
}

export interface FailRefund {
  name: 'FailRefund'
  timestamp: number
  refundId: string
  amount: number
  currency: string
}

export interface MakePayment {
  name: 'MakePayment'
  timestamp: number
  chargeId: string | null
  paymentIntentId: string | null
  paymentRecordId: string | null
  amount: number
  currency: string
}

export interface FinalizeInvoice {
  name: 'FinalizeInvoice'
  timestamp: number
}

export interface MarkUncollectibleInvoice {
  name: 'MarkUncollectibleInvoice'
  timestamp: number
}

export interface VoidInvoice {
  name: 'VoidInvoice'
  timestamp: number
}

export interface CreditCustomerBalance {
  name: 'CreditCustomerBalance'
  timestamp: number
  amount: number
  currency: string
  customerBalanceTransactionId: string
}

export interface DebitCustomerBalance {
  name: 'DebitCustomerBalance'
  timestamp: number
  amount: number
  currency: string
  customerBalanceTransactionId: string
}

export interface CreditCreditBalance {
  name: 'CreditCreditBalance'
  timestamp: number
  amount: number
  currency: string
  creditBalanceTransactionId: string
}

export interface DebitCreditBalance {
  name: 'DebitCreditBalance'
  timestamp: number
  amount: number
  currency: string
  creditBalanceTransactionId: string
}

export interface Invoice {
  id: string
  number: string | null
  total: number
  amountPaid: number
  amountOverpaid: number
  amountRemaining: number
  currency: string
  status: string
  finalized_at: string | null
  paid_at: string | null
  due_at: string | null
  marked_uncollectible_at: string | null
  voided_at: string | null
  applied_customer_balance: number
  lineItems: InvoiceLineItem[]
  payments: InvoicePayment[]
  customerBalanceTransactions: CustomerBalanceTransaction[]
  creditNotes: CreditNote[]
  billingActivities: BillingActivity[]
}

export interface InvoiceLineItem {
  id: string
  rank: number
  description: string | null
  amount: number
  currency: string
  startedAt: number | null
  endedAt: number | null
  invoiceItemId: string | null
  subscriptionItemId: string | null
  priceId: string | null
  pricingUnitAmountDecimal: string | null
  totalPrincipleAmount: number
  totalTaxAmount: number
  totalInclusiveTaxAmount: number
  totalExclusiveTaxAmount: number
  totalDiscountAmount: number
  totalPaidCreditGrantedAmount: number
  totalPromotionalCreditGrantedAmount: number
  subtotal: number
  total: number
  meterEventSummaries: MeterEventSummary[]
  price: Price | null
}

export interface InvoicePayment {
  id: string
  amountPaid: number | null
  amountRequested: number | null
  currency: string
  chargeId: string | null
  paymentIntentId: string | null
  paymentRecordId: string | null
  paymentType: string | null
  createdAt: number
  canceledAt: number | null
  paidAt: number | null
  status: string
  paidAmount: number
}

export interface CustomerBalanceTransaction {
  id: string
  amount: number
  createdAt: number
  currency: string
  customerId: string
  description: string | null
  endingBalance: number
  invoiceId: string | null
  creditNoteId: string | null
  type: string
}

export interface CreditNote {
  id: string
  type: string
  invoiceId: string
  currency: string
  total: number
  prePaymentAmount: number
  customerBalanceTransactionId: string | null
  outOfBandAmount: number | null
  createdAt: number
  effectiveAt: number | null
  voidedAt: number | null
  occurredAt: number
  customerBalanceTransaction: CustomerBalanceTransaction | null
  lines: CreditNoteLineItem[]
  refunds: CreditNoteRefund[]
}

export interface CreditNoteLineItem {
  id: string
  rank: number
  type: string
  amount: number
  description: string | null
  invoiceLineItemId: string | null
  totalPrincipleAmount: number
  totalInclusiveTaxAmount: number
  totalExclusiveTaxAmount: number
  totalDiscountAmount: number
  totalPaidCreditGrantedAmount: number
  totalPromotionalCreditGrantedAmount: number
  subtotal: number
  total: number
}

export interface CreditNoteRefund {
  id: string
  rank: number
  type: string
  amountRefunded: number
  paymentRecordRefundId: string | null
  refund: Refund | null
}

export interface Refund {
  id: string
  amount: number
  currency: string
  chargeId: string | null
  paymentIntentId: string | null
  status: string
  createdAt: number
  belongsToCreditNote: boolean
  balanceTransaction: BalanceTransaction | null
  failureBalanceTransaction: BalanceTransaction | null
}

export interface BalanceTransaction {
  id: string
}

export interface Charge {
  id: string
  balanceTransactionId: string | null
  customerId: string | null
  amount: number
  currency: string
  description: string | null
  disputed: boolean
  refunded: boolean
  amountRefunded: number | null
  paymentIntentId: string | null
  created: number
  status: string
  balanceTransaction: BalanceTransaction | null
  disputes: Dispute[]
  refunds: Refund[]
}

export interface Dispute {
  id: string
  balanceTransactionIds: string[]
  amount: number
  currency: string
  chargeId: string | null
  paymentIntentId: string | null
  status: string
  createdAt: number
  balanceTransactions: BalanceTransaction[]
}

export interface PaymentIntent {
  id: string
  customerId: string | null
  description: string | null
  latestChargeId: string | null
  charge: Charge | null
}

export interface InvoiceItem {
  id: string
  invoiceId: string | null
  customerId: string
  amount: number
  currency: string
  description: string | null
  startedAt: number | null
  endedAt: number | null
  discountIds: string[]
  taxRateIds: string[]
  createdAt: number
  discounts: Discount[]
  taxRates: TaxRate[]
  totalDiscountAmount: number
  totalPrincipleAmount: number
  totalInclusiveTaxAmount: number
  totalExclusiveTaxAmount: number
  subtotal: number
  total: number
}

export interface SubscriptionItem {
  id: string
  subscriptionId: string
  priceId: string
  quantity: number
  currentPeriodEnd: number
  currentPeriodStart: number
  discountIds: string[]
  taxRateIds: string[]
  subscription: Subscription
  price: Price | null
  meterEventSummaries: MeterEventSummary[]
  discounts: Discount[]
  taxRates: TaxRate[]
}

export interface Subscription {
  id: string
  customerId: string
  currency: string
  status: string
  startDate: number
  discountIds: string[]
  defaultTaxRateIds: string[]
  discounts: Discount[]
  defaultTaxRates: TaxRate[]
}

export interface Price {
  id: string
  currency: string
  productId: string
  type: string
  billingScheme: string
  unitAmount: number
  tiersMode: string | null
  recurringInterval: string | null
  recurringIntervalCount: number | null
  recurringMeterId: string | null
  recurringUsageType: string | null
  tiers: PriceTier[]
}

export interface PriceTier {
  flatAmount: number | null
  unitAmount: number | null
  upTo: number | null
}

export interface Discount {
  id: string
  couponId: string | null
  coupon: Coupon | null
}

export interface Coupon {
  id: string
  amountOff: number | null
  currency: string | null
  percentOff: number | null
}

export interface TaxRate {
  id: string
  inclusive: boolean
  percentage: number
  flatAmount: number | null
  flatAmountCurrency: string | null
  rateType: string | null
}

export interface MeterEventSummary {
  id: string
  aggregatedValue: number
  meterId: string
  customerId: string
  startTime: number
  endTime: number
}

export interface RevRecTransactionDetailLineItem {
  id: string | null,
  description: string | null,
  principleAmount: number,
  startedAt: number | null,
  endedAt: number | null,
  inclusiveTaxAmount: number,
  discountAmount: number,
  paidCreditGrantAmount: number,
  promotionalCreditGrantAmount: number,
  subtotal: number,
  exclusiveTaxAmount: number,
  total: number
}

export interface RevRecTransactionDetailUsage {
  description: string | null,
  startedAt: number,
  endedAt: number,
  value: number
}

export interface RevRecTransactionDetail {
  currency: string
  total: number | null
  outstanding: number | null
  paid: number | null
  status: string
  lineItems: RevRecTransactionDetailLineItem[]
  usages: RevRecTransactionDetailUsage[]
  billingActivities: BillingActivity[]
}

export interface StripeAccount {
  id: string
  name: string
  defaultCurrency: string
  liveModeEnabled: boolean
  testModeEnabled: boolean
}

export interface CurrentStripeAccount {
  stripeAccount: StripeAccount
  liveMode: boolean
}

export interface TrackedException {
  createdAt: number
  exceptionClass: string
  message: string
  stackTrace: string
}
