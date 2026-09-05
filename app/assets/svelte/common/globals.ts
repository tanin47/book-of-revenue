import type {User, CurrentStripeAccount} from './models'

// @ts-expect-error defined globally
export const LOGGED_IN_USER: User | null = window.LOGGED_IN_USER
// @ts-expect-error defined globally
export let LATEST_ACCOUNTING_PERIOD: number = window.LATEST_ACCOUNTING_PERIOD
// @ts-expect-error defined globally
export let FIRST_ACCOUNTING_PERIOD: number = window.FIRST_ACCOUNTING_PERIOD
// @ts-expect-error defined globally
export let CURRENT_STRIPE_ACCOUNT: CurrentStripeAccount | null = window.CURRENT_STRIPE_ACCOUNT
// @ts-expect-error defined globally
export let CURRENT_CURRENCY: string = window.CURRENT_CURRENCY

export function getVersionedAsset(path: string): string {
  // @ts-expect-error defined globally
  const versionedAssets = window.VERSIONED_ASSETS;
  if (!versionedAssets) {
    return `/assets/${path}`;
  }

  return `/assets/${versionedAssets[path]}`;
}

export function addMonths(ms: number, months: number): number {
  const date = new Date(ms);
  date.setUTCMonth(date.getUTCMonth() + months);
  return date.getTime();
}

export function addYears(ms: number, years: number): number {
  const date = new Date(ms);
  date.setFullYear(date.getFullYear() + years);
  return date.getTime();
}

export function formatAccountingPeriod(ms: number): string {
  return new Date(ms).toISOString().substring(0, 7);
}

export function formatDate(ms: number): string {
  return new Date(ms).toISOString().substring(0, 10);
}

export function formatDateTime(ms: number): string {
  return new Date(ms).toISOString().substring(0, 19).replace('T', ' ').replace('Z', '');
}

export function formatAmount(value: number, currency: string): string {
  return new Intl.NumberFormat('en-US', {style: 'currency', currency: currency}).format(value/100);
}

export function formatNumber(num: number, exactFractionDigits: number = 0): string {
  return new Intl.NumberFormat('en-US', {minimumFractionDigits: exactFractionDigits, maximumFractionDigits: exactFractionDigits}).format(num);
}

type GenericFunction = (...args: any[]) => void;

/**
 * Creates a debounced function that delays invoking `func`
 * until after `delay` milliseconds have elapsed since the last time it was invoked.
 */
export function debounce<T extends GenericFunction>(
  func: T,
  delay: number = 300
): (...args: Parameters<T>) => void {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;

  return function (...args: Parameters<T>): void {
    // Clear any existing timer so we don't fire early
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }

    // Set a new timer
    timeoutId = setTimeout(() => {
      func(...args);
      timeoutId = null;
    }, delay);
  };
}

export function getValidPeriods(): number[] {
  const validPeriods: number[] = []
  let current = FIRST_ACCOUNTING_PERIOD
  while (current <= LATEST_ACCOUNTING_PERIOD) {
    validPeriods.push(current)
    current = Date.UTC(new Date(current).getUTCFullYear(), new Date(current).getUTCMonth() + 1, 1)
  }
  return validPeriods
}

export function getQueryParam(paramName: string): string | null {
  const urlParams = new URLSearchParams(window.location.search);
  return urlParams.get(paramName);
}

export function getNetRevenueAuditUrl(period: number, groupBy: 'Customer' | 'Transaction', only: string | null): string {
  const params = {
    start: formatAccountingPeriod(period),
    end: formatAccountingPeriod(period),
    group: groupBy,
    ...only ? {only, sort: `${only}.Desc`} : {},
  }
  return `/net-revenue?${new URLSearchParams(params).toString()}`
}

function getCustomerAuditQueryString(period: number): string {
  const start = formatAccountingPeriod(addMonths(period, -6))
  const end = formatAccountingPeriod(Math.min(LATEST_ACCOUNTING_PERIOD, addMonths(period, 6)))
  const selected = formatAccountingPeriod(period)
  return new URLSearchParams({start, end, h: selected, sort: `${selected}.Desc`}).toString()
}

export function getMonthlyArpaAuditUrl(period: number): string {
  return `/customer?${getCustomerAuditQueryString(period)}`
}

export function getMonthlyNrrAuditUrl(period: number): string {
  return `/customer/nrr?${getCustomerAuditQueryString(period)}`
}

export function getMonthlyGrrAuditUrl(period: number): string {
  return `/customer/grr?${getCustomerAuditQueryString(period)}`
}

export function getDeferredRevenueAuditUrl(period: number): string {
  return `/customer/deferred-revenue?${getCustomerAuditQueryString(period)}`
}

export function getDeferredRevenueChangeAuditUrl(period: number): string {
  const selected = formatAccountingPeriod(period)
  return `/deferred-revenue?start=${selected}&end=${selected}&group=Customer&sort=NetChange.Desc`
}

export function getOtherContractualLiabilitiesAuditUrl(period: number): string {
  return `/customer/other-contractual-liabilities?${getCustomerAuditQueryString(period)}`
}

export function getDirectCashFlowAuditUrl(period: number): string {
  const selected = formatAccountingPeriod(period)
  return `/direct-cash-flow?start=${selected}&end=${selected}&group=Customer&sort=NetChange.Desc`
}


export function getArAgingAuditUrl(until: number): string {
  return `/ar-aging?until=${formatDate(until)}&group=Customer`
}

export function makeStripeUrl(path: string): string {
  const testPortion = CURRENT_STRIPE_ACCOUNT?.liveMode ? '' : '/test'
  return `https://dashboard.stripe.com/${CURRENT_STRIPE_ACCOUNT!.stripeAccount.id}${testPortion}${path}`
}
