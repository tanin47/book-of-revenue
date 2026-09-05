<script lang="ts">
import type {BillingActivity, RevRecTransaction, RevRecTransactionDetail} from "../../common/models";
import {formatAmount, formatDateTime, formatNumber, getQueryParam, makeStripeUrl} from "../../common/globals";
import BillingActivityLine, {type RelatedId} from "./_billing_activity_line.svelte";
import {onMount} from "svelte";
import {post} from "../../common/form";
import TransactionStatus from '../../common/_transaction_status.svelte'

export let transaction: RevRecTransaction;

let detail: RevRecTransactionDetail | null = null;
let highlightedLineItemId: string | null = null;

async function load() {
  try {
    const json = await post('/load-transaction-detail', {transactionId: transaction.id})

    detail = json.detail;
  } catch (e) {
    console.error(e);
  }
}

onMount(() => {
  void load();
})

function getBillingActivityColor(activityName: string) {
  switch (activityName) {
    case 'FinalizeInvoice':
      return 'bg-base-content/30';
    case 'MakePayment':
    case 'DebitCustomerBalance':
    case 'DebitCreditBalance':
    case 'WinDispute':
      return 'bg-success';
    case 'CreditCustomerBalance':
    case 'CreditCreditBalance':
    case 'MarkUncollectibleInvoice':
    case 'IssueRefund':
    case 'FileDispute':
    case 'IssueOutOfBandRefund':
      return 'bg-neutral';
    case 'VoidInvoice':
      return 'bg-info';
    default:
      return 'bg-base-content/30';
  }
}

function getTitle(activity: BillingActivity): string {
  switch (activity.name) {
    case 'FinalizeInvoice':
      return 'Finalized invoice';
    case 'MarkUncollectibleInvoice':
      return 'Marked as uncollectible';
    case 'VoidInvoice':
      return 'Voided';
    case 'IssueCreditNote':
      return `${formatAmount(activity.amount, activity.currency)} credit note was issued`
    case 'VoidCreditNote':
      return `${formatAmount(activity.amount, activity.currency)} credit note was voided`
    case 'IssueRefund':
      return `${formatAmount(activity.amount, activity.currency)} refunded`
    case 'IssueOutOfBandRefund':
      return `${formatAmount(activity.amount, activity.currency)} out-of-band refunded`
    case 'FailRefund':
      return `${formatAmount(activity.amount, activity.currency)} refund failed`
    case 'MakePayment':
      return `${formatAmount(activity.amount, activity.currency)} paid`;
    case 'CreditCustomerBalance':
      return `${formatAmount(activity.amount, activity.currency)} customer balance credited`;
    case 'DebitCustomerBalance':
      return `${formatAmount(activity.amount, activity.currency)} customer balance debited`;
    case 'CreditCreditBalance':
      return `${formatAmount(activity.amount, activity.currency)} credit balance credited`;
    case 'DebitCreditBalance':
      return `${formatAmount(activity.amount, activity.currency)} credit balance debited`;
    case 'FileDispute':
      return `${formatAmount(activity.amount, activity.currency)} disputed`
    case 'WinDispute':
      return `${formatAmount(activity.amount, activity.currency)} dispute won`;
    default:
      return (activity as any).name
  }
}

function getTitleColorClass(activity: BillingActivity): string {
  switch (activity.name) {
    case 'MakePayment':
    case 'DebitCustomerBalance':
    case 'DebitCreditBalance':
    case 'WinDispute':
      return 'text-success';
    case 'CreditCustomerBalance':
    case 'CreditCreditBalance':
    case 'MarkUncollectibleInvoice':
    case 'IssueRefund':
    case 'FileDispute':
    case 'IssueOutOfBandRefund':
      return 'text-neutral';
    case 'VoidInvoice':
      return 'text-info';
    default:
      return activity.name;
  }
}

function getRelatedId(activity: BillingActivity, transaction: RevRecTransaction): RelatedId | null {
  switch (activity.name) {
    case 'MakePayment':
      const id = activity.paymentRecordId ?? activity.paymentIntentId ?? activity.chargeId;

      if (id) {
        return {id, url: makeStripeUrl(`/payments/${id}`)}
      } else {
        return null;
      }
    case 'CreditCustomerBalance':
    case 'DebitCustomerBalance':
      return {
        id: activity.customerBalanceTransactionId,
        url: transaction.customerId ? makeStripeUrl(`/customers/${transaction.customerId}/balance_transactions`) : null
      }
    case 'IssueCreditNote':
    case 'VoidCreditNote':
      return {
        id: activity.creditNoteId,
        url: makeStripeUrl(`/credit_notes/${activity.creditNoteId}`)
      };
    case 'IssueRefund':
    case 'FailRefund':
      return {
        id: activity.refundId,
        url: makeStripeUrl(`/refunds/${activity.refundId}`)
      };
    case 'FileDispute':
    case 'WinDispute':
      return {
        id: activity.disputeId,
        url: makeStripeUrl(`/disputes/${activity.disputeId}`)
      };
    default:
      return null;
  }
}

</script>

{#if detail}
<div class="flex flex-col gap-6 p-4 text-xs w-[350px] min-w-[350px] max-w-[350px]">
  <div class="grid grid-cols-4 gap-1">
    <div class="border border-base-300 px-2 py-1 flex flex-col gap-0.5 text-ellipsis overflow-hidden">
      <span class="uppercase text-base-content text-ellipsis overflow-hidden">Total</span>
      <span class="font-semibold">
        {#if detail.total !== null}
          {formatAmount(detail.total, detail.currency)}
        {:else}
          -
        {/if}
      </span>
    </div>
    <div class="border border-base-300 px-2 py-1 flex flex-col gap-0.5 text-ellipsis overflow-hidden">
      <span class="uppercase text-base-content text-ellipsis overflow-hidden">Paid</span>
      <span class="font-semibold text-success">
        {#if detail.paid !== null}
          {formatAmount(detail.paid, detail.currency)}
        {:else}
          -
        {/if}
      </span>
    </div>
    <div class="border border-base-300 px-2 py-1 flex flex-col gap-0.5 text-ellipsis overflow-hidden">
      <span class="uppercase text-base-content text-ellipsis overflow-hidden">Outstanding</span>
      <span class="font-semibold">
        {#if detail.outstanding !== null}
          {formatAmount(detail.outstanding, detail.currency)}
        {:else}
          -
        {/if}
      </span>
    </div>
    <div class="border border-base-300 px-2 py-1 flex flex-col gap-1 text-ellipsis overflow-hidden">
      <span class="uppercase text-base-content text-ellipsis overflow-hidden">Status</span>
      <TransactionStatus value={detail.status} />
    </div>
  </div>

  {#if detail.lineItems.length > 0}
    <div class="flex flex-col gap-2 text-xs">
      <span class="font-semibold uppercase text-base-content">Line items</span>
      <div class="flex flex-col gap-2 text-base-content">
        {#each detail.lineItems as lineItem, index (index)}
          <div class="w-full border border-base-300">
            <div
              class="bg-base-200 px-2 py-1 font-medium whitespace-nowrap text-ellipsis overflow-hidden"
              title={lineItem.description ?? 'No description'}
            >
              <a href="/customer/line-item/{lineItem.id}" class="link">{lineItem.description ?? 'No description'}</a>
            </div>
            <div class="flex flex-col divide-y divide-base-300">
              {#if lineItem.id}
                <div class="flex items-center justify-between gap-2 px-2 py-1">
                  <span>ID</span>
                  <span>{lineItem.id}</span>
                </div>
              {/if}
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Principal</span>
                <span class="font-medium">{formatAmount(lineItem.principleAmount, detail.currency)}</span>
              </div>
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Start</span>
                <span>{lineItem.startedAt ? formatDateTime(lineItem.startedAt) : '-'}</span>
              </div>
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>End</span>
                <span>{lineItem.endedAt ? formatDateTime(lineItem.endedAt) : '-'}</span>
              </div>
              {#if lineItem.discountAmount !== 0}
                <div class="flex items-center justify-between gap-2 px-2 py-1">
                  <span>Discounts</span>
                  <span>{formatAmount(lineItem.discountAmount, detail.currency)}</span>
                </div>
              {/if}
              {#if lineItem.paidCreditGrantAmount !== 0}
                <div class="flex items-center justify-between gap-2 px-2 py-1">
                  <span>Paid credit grants</span>
                  <span>{formatAmount(lineItem.paidCreditGrantAmount, detail.currency)}</span>
                </div>
              {/if}
              {#if lineItem.promotionalCreditGrantAmount !== 0}
                <div class="flex items-center justify-between gap-2 px-2 py-1">
                  <span>Promo credit grants</span>
                  <span>{formatAmount(lineItem.promotionalCreditGrantAmount, detail.currency)}</span>
                </div>
              {/if}
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Inclusive tax</span>
                <span>{formatAmount(lineItem.inclusiveTaxAmount, detail.currency)}</span>
              </div>
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Subtotal</span>
                <span>{formatAmount(lineItem.subtotal, detail.currency)}</span>
              </div>
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Exclusive tax</span>
                <span>{formatAmount(lineItem.exclusiveTaxAmount, detail.currency)}</span>
              </div>
              <div class="flex items-center justify-between gap-2 px-2 py-1">
                <span>Total</span>
                <span>{formatAmount(lineItem.total, detail.currency)}</span>
              </div>
            </div>
          </div>
        {/each}
      </div>
    </div>
  {/if}

  {#if detail.usages.length > 0}
    <div class="flex flex-col gap-2">
      <span class="text-xs font-semibold uppercase text-base-content">Usages</span>
      <div class="overflow-x-auto">
      <table class="table table-xs border border-base-300">
        <thead class="bg-base-200 text-base-content text-xs">
        <tr>
          <th>LineItem</th>
          <th>Start</th>
          <th>End</th>
          <th>Value</th>
        </tr>
        </thead>
        <tbody class="text-base-content text-xs">
        {#each detail.usages as usage, index (index)}
          <tr>
            <td class="whitespace-nowrap text-ellipsis overflow-hidden">{usage.description ?? 'No description'}</td>
            <td class="whitespace-nowrap font-mono">{formatDateTime(usage.startedAt)}</td>
            <td class="whitespace-nowrap font-mono">{formatDateTime(usage.endedAt)}</td>
            <td>{formatNumber(usage.value)}</td>
          </tr>
        {/each}
        </tbody>
      </table>
      </div>
    </div>
  {/if}

  {#if detail.billingActivities.length > 0}
    <div class="flex flex-col gap-3">
      <span class="text-xs font-semibold uppercase text-base-content">Billing activities</span>
      <div class="flex flex-col gap-8 border-s border-base-300 ps-4 text-xs w-full max-w-[350px]">
        {#each detail.billingActivities ?? [] as activity, index (index)}
          <div class="relative min-w-0 flex flex-col gap-2">
            <span class="absolute top-[3px] -start-5 size-2 rounded-full {getBillingActivityColor(activity.name)} ring-2 ring-base-100"></span>
            <BillingActivityLine
              timestamp={activity.timestamp}
              title={getTitle(activity)}
              titleColorClass={getTitleColorClass(activity)}
              relatedId={getRelatedId(activity, transaction)}
            />
            {#if activity.name === 'IssueCreditNote'}
              {@const creditNote = transaction.invoice?.creditNotes.find(c => c.id === activity.creditNoteId)}
              {#if creditNote && creditNote.lines.length > 0}
                <div class="mt-2 flex flex-col gap-2 text-base-content">
                  {#each creditNote.lines as line (line.id)}
                    <div class="w-full border border-base-300">
                      <div class="bg-base-200 px-2 py-1 font-medium whitespace-nowrap text-ellipsis overflow-hidden">
                        {line.description ?? 'No description'}
                      </div>
                      <div class="flex flex-col divide-y divide-base-300">
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>ID</span>
                          <span>{line.id}</span>
                        </div>
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Line item</span>
                          <span>{line.invoiceLineItemId ?? '-'}</span>
                        </div>
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Principle</span>
                          <span class="font-medium">{formatAmount(line.totalPrincipleAmount, creditNote.currency)}</span>
                        </div>
                        {#if line.totalDiscountAmount !== 0}
                          <div class="flex items-center justify-between gap-2 px-2 py-1">
                            <span>Discounts</span>
                            <span>{formatAmount(line.totalDiscountAmount, creditNote.currency)}</span>
                          </div>
                        {/if}
                        {#if line.totalPaidCreditGrantedAmount !== 0}
                          <div class="flex items-center justify-between gap-2 px-2 py-1">
                            <span>Paid credit grants</span>
                            <span>{formatAmount(line.totalPaidCreditGrantedAmount, creditNote.currency)}</span>
                          </div>
                        {/if}
                        {#if line.totalPromotionalCreditGrantedAmount !== 0}
                          <div class="flex items-center justify-between gap-2 px-2 py-1">
                            <span>Promo credit grants</span>
                            <span>{formatAmount(line.totalPromotionalCreditGrantedAmount, creditNote.currency)}</span>
                          </div>
                        {/if}
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Inclusive tax</span>
                          <span>{formatAmount(line.totalInclusiveTaxAmount, creditNote.currency)}</span>
                        </div>
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Subtotal</span>
                          <span>{formatAmount(line.subtotal, creditNote.currency)}</span>
                        </div>
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Exclusive tax</span>
                          <span>{formatAmount(line.totalExclusiveTaxAmount, creditNote.currency)}</span>
                        </div>
                        <div class="flex items-center justify-between gap-2 px-2 py-1">
                          <span>Total</span>
                          <span>{formatAmount(line.total, creditNote.currency)}</span>
                        </div>
                      </div>
                    </div>
                  {/each}
                </div>
              {/if}
            {/if}
          </div>
        {/each}
      </div>
    </div>
  {/if}
</div>
{/if}
