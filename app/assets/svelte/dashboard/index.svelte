<script lang="ts">
import Layout from './_layout.svelte'
import NetRevenueChart from './_net_revenue_chart.svelte'
import ActiveCustomerChart from './_active_customer_chart.svelte'
import MonthlyArpaChart from './_monthly_arpa_chart.svelte'
import MonthlyNrrChart from './_monthly_nrr_chart.svelte'
import DeferredRevenueChart from './_deferred_revenue_chart.svelte'
import MonthlyGrrChart from './_monthly_grr_chart.svelte'
import DeferredRevenueChangeChart from './_deferred_revenue_change_chart.svelte'
import OtherContractualLiabilityChart from './_other_contractual_liability_chart.svelte'
import OtherContractualLiabilityChangeChart from './_other_contractual_liability_change_chart.svelte'
import ArAgingChart from './_ar_aging_chart.svelte'
import DirectCashFlowChart from './_direct_cash_flow_chart.svelte'
import {
  addMonths,
  CURRENT_CURRENCY,
  FIRST_ACCOUNTING_PERIOD,
  formatAccountingPeriod,
  formatAmount,
  formatNumber,
  getDeferredRevenueAuditUrl, getDirectCashFlowAuditUrl,
  getMonthlyArpaAuditUrl,
  getMonthlyGrrAuditUrl,
  getMonthlyNrrAuditUrl,
  getNetRevenueAuditUrl, getOtherContractualLiabilitiesAuditUrl,
  LATEST_ACCOUNTING_PERIOD
} from "../common/globals";
import Button from "../common/_button.svelte";
import FilterDialog from "./_filter_dialog.svelte";
import {post} from "../common/form";
import type {DataPoint} from "./_bar_chart.svelte";

const CHARTS = [
  {key: 'netRevenue', name: 'Net revenue', component: NetRevenueChart},
  {key: 'deferredRevenue', name: 'Deferred revenue ending balance', component: DeferredRevenueChart},
  {key: 'deferredRevenueChanges', name: 'Deferred revenue change', component: DeferredRevenueChangeChart},
  {key: 'activeCustomers', name: 'Active customers', component: ActiveCustomerChart},
  {key: 'monthlyArpa', name: 'Monthly average ARPA', component: MonthlyArpaChart},
  {key: 'monthlyNrr', name: 'Monthly median NRR', component: MonthlyNrrChart},
  {key: 'monthlyGrr', name: 'Monthly average GRR', component: MonthlyGrrChart},
  {key: 'otherContractualLiabilities', name: 'Other contractual liability ending balance', component: OtherContractualLiabilityChart},
  {key: 'otherContractualLiabilityChanges', name: 'Other contractual liability change', component: OtherContractualLiabilityChangeChart},
  {key: 'arAging', name: 'AR aging', component: ArAgingChart},
  {key: 'directCashFlow', name: 'Direct cash flow', component: DirectCashFlowChart},
]

const METRICS: {key: string, name: string, type: 'amount' | 'percentage' | 'value'}[] = [
  {key: 'netRevenue', name: 'Net revenue', type: 'amount'},
  {key: 'deferredRevenue', name: 'DR ending balance', type: 'amount'},
  {key: 'activeCustomers', name: 'Active customers', type: 'value'},
  {key: 'monthlyArpa', name: 'Monthly average ARPA', type: 'amount'},
  {key: 'monthlyNrr', name: 'Monthly median NRR', type: 'percentage'},
  {key: 'monthlyGrr', name: 'Monthly average GRR ', type: 'percentage'},
  {key: 'otherContractualLiabilities', name: 'Other contractual liabilities', type: 'amount'},
  {key: 'directCashFlow', name: 'Direct cash flow', type: 'amount'},
]

let params = {
  periodStart: Math.max(addMonths(LATEST_ACCOUNTING_PERIOD, -11), FIRST_ACCOUNTING_PERIOD),
  periodEnd: LATEST_ACCOUNTING_PERIOD,
  currency: CURRENT_CURRENCY
}


$: if (params) {
  load()
}

let isLoading = false

let result = {
  netRevenue: [] as DataPoint[],
  activeCustomers: [] as DataPoint[],
  monthlyArpa: [] as DataPoint[],
  monthlyNrr: [] as DataPoint[],
  monthlyGrr: [] as DataPoint[],
  deferredRevenue: [] as DataPoint[],
  deferredRevenueChanges: [] as DataPoint[],
  otherContractualLiabilities: [] as DataPoint[],
  otherContractualLiabilityChanges: [] as DataPoint[],
  arAging: [] as DataPoint[],
  directCashFlow: [] as DataPoint[],
}

interface OverviewRow {
  period: number
  netRevenue: number
  activeCustomers: number
  monthlyArpa: number
  monthlyNrr: number
  monthlyGrr: number
  deferredRevenue: number
  otherContractualLiabilities: number
  directCashFlow: number
}

let overviewRows: OverviewRow[] = []

function computeOverview() {
  overviewRows = []
  for (let i = 0; i < 6; i++) {
    const index = result.netRevenue.length - 1 - i
    let directCashFlow = 0
    result.directCashFlow[index].values!.forEach((amount) => directCashFlow += amount.value)
    let netRevenue = 0
    result.netRevenue[index].values!.forEach((amount) => netRevenue += amount.value)
    overviewRows.push({
      period: result.netRevenue[index].period!,
      netRevenue: netRevenue,
      activeCustomers: result.activeCustomers[index].value!,
      monthlyArpa: result.monthlyArpa[index].value!,
      monthlyNrr: result.monthlyNrr[index].value!,
      monthlyGrr: result.monthlyGrr[index].value!,
      deferredRevenue: result.deferredRevenue[index].value!,
      otherContractualLiabilities: result.otherContractualLiabilities[index].value!,
      directCashFlow: directCashFlow
    })
  }

}

async function load(): Promise<void> {
  isLoading = true

  try {
    const json = await post('/load-overview', params)

    result = json
    computeOverview()
  } catch (e) {
    console.error(e)
  } finally {
    isLoading = false
  }
}

function computeDelta(metricType: 'amount' | 'percentage' | 'value', value: number | null, prevValue: number | null): 'inf' | '-inf' | number {
  if (metricType === 'percentage') {
    return (value ?? 0) - (prevValue ?? 0)
  }

  if (prevValue === null || prevValue === 0) {
    if (value !== null && value > 0) {
      return 'inf'
    } else if (value !== null && value < 0) {
      return '-inf'
    } else {
      return 0
    }
  }

  return ((value ?? 0) - prevValue) * 100 / prevValue
}

function computeDeltaColorClass(delta: 'inf' | '-inf' | number): string {
  if (delta === 'inf') { return 'text-success' }
  if (delta === '-inf') { return 'text-error' }
  if (delta === 0) { return 'text-gray-400' }
  return delta > 0 ? 'text-success' : 'text-error'
}

function getAuditUrl(metricKey: string, period: number): string {
  switch (metricKey) {
    case 'netRevenue':
      return getNetRevenueAuditUrl(period, 'Transaction', null)
    case 'activeCustomers':
      return getNetRevenueAuditUrl(period, 'Customer', 'NetRevenue')
    case 'monthlyArpa':
      return getMonthlyArpaAuditUrl(period)
    case 'monthlyNrr':
      return getMonthlyNrrAuditUrl(period)
    case 'monthlyGrr':
      return getMonthlyGrrAuditUrl(period)
    case 'deferredRevenue':
      return getDeferredRevenueAuditUrl(period)
    case 'otherContractualLiabilities':
      return getOtherContractualLiabilitiesAuditUrl(period)
    case 'directCashFlow':
      return getDirectCashFlowAuditUrl(period)
    default:
      return '/'
  }
}

let filterDialog: FilterDialog
</script>

<FilterDialog
  bind:this={filterDialog}
  onSubmitted={async (newParams) => {
    params = newParams
  }}
/>

<Layout>
  <div class="flex justify-between gap-2 items-stretch bg-base-200 text-sm text-primary border-base-content border-b">
    <div class="flex items-stretch gap-0">
      <div class="text-xs px-2 py-1 flex items-center gap-2">
        <Button class="btn btn-xs btn-secondary shadow-none" onClick={() => {filterDialog.open(params)}}>Filter</Button>
        <div class="flex items-center gap-1">
          <span class="font-bold">Period:</span>
          <span>{new Date(params.periodStart).toISOString().substring(0, 7)} to {new Date(params.periodEnd).toISOString().substring(0, 7)}</span>
        </div>
      </div>
    </div>
    <div class="flex items-stretch justify-stretch gap-0">
      <div class="px-2 py-1 flex items-center">
      </div>
    </div>
  </div>
  <div class="flex-1 min-h-0 overflow-y-auto p-4">
    <div class="card bg-base-100 mb-4">
      <div class="card-body p-0 overflow-x-auto">
        <table class="w-full text-xs border-collapse">
          <thead>
            <tr class="border-b border-base-300">
              <th class="sticky left-0 bg-base-100 text-left font-semibold text-primary px-2 py-1.5 align-bottom whitespace-nowrap">Last {overviewRows.length} periods</th>
              {#each METRICS as metric (metric.key)}
                <th class="text-right font-semibold text-primary px-2 py-1.5 align-bottom max-w-[100px]">{metric.name}</th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each overviewRows as overview, index (overview.period)}
              <tr class="border-b border-base-200">
                <td class="sticky left-0 bg-base-100 px-2 py-1.5 whitespace-nowrap">
                  <div class="flex flex-col leading-tight">
                    <span class="font-semibold">{formatAccountingPeriod(overview.period)}</span>
                    <span class="text-[10px] text-gray-400">
                      {#if overview.period === LATEST_ACCOUNTING_PERIOD}
                        as of now
                      {/if}
                    </span>
                  </div>
                </td>
                {#each METRICS as metric (metric.key)}
                  {@const value = ((overview as any)[metric.key] as number) ?? null}
                  {@const prevValue = (value !== null && (index + 1) < overviewRows.length) ? ((overviewRows as any)[index + 1][metric.key] as number) : null}
                  <td class="px-2 py-1.5 text-right whitespace-nowrap">
                    <div class="flex flex-col items-end leading-tight">
                      <a href={getAuditUrl(metric.key, overview.period)} class="text-sm font-bold cursor-pointer underline decoration-dotted decoration-current">
                        {#if value === null}
                          -
                        {:else if metric.type === 'amount'}
                          {formatAmount(value, 'usd')}
                        {:else if metric.type === 'percentage'}
                          {value.toFixed(2)}%
                        {:else}
                          {formatNumber(value)}
                        {/if}
                      </a>
                      {#if prevValue !== null}
                        {@const delta = computeDelta(metric.type, value, prevValue)}
                        <span class="inline-flex items-center gap-0.5 text-[10px] font-medium {computeDeltaColorClass(delta)}">
                          {#if delta === 'inf'}
                            <i class="ph-bold ph-arrow-up"></i>
                          {:else if delta === '-inf'}
                            <i class="ph-bold ph-arrow-down"></i>
                          {:else if delta > 0}
                            <i class="ph-bold ph-arrow-up"></i>
                          {:else if delta < 0}
                            <i class="ph-bold ph-arrow-down"></i>
                          {:else}
                            <i class="ph-bold ph-minus"></i>
                          {/if}
                          <span>
                            {#if delta === 'inf' || delta === '-inf'}
                              &#8734;%
                            {:else}
                              {delta.toFixed(2)}%
                            {/if}
                          </span>
                        </span>
                      {/if}
                    </div>
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </div>
    <div class="grid grid-cols-3 gap-4 w-full">
      {#each CHARTS as chart (chart.name)}
        <div class="card bg-base-100 w-full">
          <div class="card-body">
            <svelte:component this={chart.component} {...{params: params, points: (result as any)[chart.key]}} />
          </div>
        </div>
      {/each}
    </div>
  </div>
</Layout>


<style lang="scss">
</style>
