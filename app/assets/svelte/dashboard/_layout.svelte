<script lang="ts">
import StripeAccountSelector from "./_stripe_account_selector.svelte";
import CurrencySelector from "./_currency_selector.svelte";
import {onMount} from "svelte";

const LEFT_NAV_WIDTH_KEY = 'leftNavWidth'
const MIN_LEFT_NAV_WIDTH = 45
const MAX_LEFT_NAV_WIDTH = 400

let leftNavWidth = 200;

type ResizeMode = 'leftnav' | null
let resizeMode: ResizeMode = null

function clampLeftNavWidth(width: number): number {
  return Math.min(Math.max(width, MIN_LEFT_NAV_WIDTH), MAX_LEFT_NAV_WIDTH)
}

onMount(() => {
  try {
    const saved = Number(window.localStorage.getItem(LEFT_NAV_WIDTH_KEY))

    if (Number.isFinite(saved) && saved > 0) {
      leftNavWidth = clampLeftNavWidth(saved)
    }
  } catch (e) {
    console.error('Unable to read the left nav width: ', e)
  }
})

function startResize(mode: ResizeMode) {
  resizeMode = mode;
  document.body.classList.add('resizing');
}

function stopResize() {
  if (resizeMode === 'leftnav') {
    try {
      window.localStorage.setItem(LEFT_NAV_WIDTH_KEY, `${leftNavWidth}`)
    } catch (e) {
      console.error('Unable to save the left nav width: ', e)
    }
  }

  resizeMode = null;
  document.body.classList.remove('resizing');
}

function handleResize(event: MouseEvent) {
  switch (resizeMode) {
    case 'leftnav':
      leftNavWidth = clampLeftNavWidth(event.clientX);
      break;
    case null:
    // do nothing
  }
}

const MENU_ITEMS = [
  {label: 'Overview', icon: 'ph-house', path: '/overview'},
  {label: 'Explore by', type: 'section'},
  {label: 'Customers', icon: 'ph-user-list', path: '/customer'},
  {label: 'Products', icon: 'ph-storefront', path: '/product'},
  {label: 'Countries', icon: 'ph-globe-hemisphere-west', path: null},
  {label: 'Schedules & Metrics', type: 'section'},
  {label: 'Net Revenue', icon: 'ph-money-wavy', path: '/net-revenue'},
  {label: 'Net Revenue Waterfall', icon: 'ph-steps', path: '/revenue-waterfall'},
  {label: 'AR Aging', icon: 'ph-invoice', path: '/ar-aging'},
  {label: 'Deferred Revenue', icon: 'ph-clock-countdown', path: '/deferred-revenue'},
  {label: 'Direct Cash Flow', icon: 'ph-cash-register', path: '/direct-cash-flow'},
  {label: 'Ledger & Statements', type: 'section'},
  {label: 'Debits & Credits', icon: 'ph-table', path: '/debits-and-credits'},
  {label: 'Income Statement', icon: 'ph-currency-circle-dollar', path: '/income-statement'},
  {label: 'Balance Sheet', icon: 'ph-notebook', path: '/balance-sheet'},
  {label: 'Management', type: 'section'},
  {label: 'Engine Stats', icon: 'ph-engine', path: '/engine'},
  {label: 'Settings', icon: 'ph-gear', path: '/settings'}
]

</script>

<svelte:window on:mousemove={handleResize} on:mouseup={stopResize}/>

<div class="relative h-full w-full flex flex-row items-stretch">
  <div
    class="flex flex-col justify-between bg-primary-content border-e-2 border-gray-600 relative overflow-hidden"
    style="width: {leftNavWidth}px; min-width: {leftNavWidth}px; max-width: {leftNavWidth}px;"
  >
      <span
        class="absolute top-0 bottom-0 right-0 w-[5px] z-50 cursor-col-resize"
        onmousedown={() => {startResize('leftnav')}}
      >
        &nbsp;
      </span>
    <div class="overflow-auto">
      <div class="flex flex-col gap-0 p-1">
        <StripeAccountSelector />
        <CurrencySelector />
        {#each MENU_ITEMS as item, index (index)}
          {#if item.type === 'section'}
            <div class="px-2 pt-4 pb-1 overflow-hidden">
              <span class="block text-[11px] font-semibold uppercase tracking-wider text-gray-600 whitespace-nowrap overflow-hidden">{item.label}</span>
            </div>
          {:else if !item.path}
            <div class="py-1 rounded cursor-not-allowed overflow-hidden opacity-50" >
              <div class="flex items-center gap-1 px-2 py-1 rounded">
                <i class="ph-duotone {item.icon} text-base w-[16px]"></i>
                <span class="text-sm whitespace-nowrap overflow-hidden grow">{item.label}</span>
                <span class="badge badge-xs w-fit whitespace-nowrap overflow-hidden shrink">Coming soon!</span>
              </div>
            </div>
          {:else}
            <a
              href={item.path}
              class="py-1 rounded group cursor-pointer overflow-hidden"
            >
              <div class="flex items-center gap-1 px-2 py-1 rounded group-hover:bg-base-200 {window.location.pathname.startsWith(item.path) ? 'font-bold text-secondary' : ''}">
                <i class="ph-duotone {item.icon} text-base w-[16px]"></i>
                <span class="text-sm whitespace-nowrap overflow-hidden">{item.label}</span>
              </div>
            </a>
          {/if}
        {/each}
        <a
          href="/logout"
          class="py-1 rounded group cursor-pointer overflow-hidden"
        >
          <div class="flex items-center gap-1 px-2 py-1 rounded group-hover:bg-base-200 text-neutral">
            <i class="ph-duotone ph-sign-out text-base w-[16px]"></i>
            <span class="text-sm text-ellipsis whitespace-nowrap overflow-hidden">Log out</span>
          </div>
        </a>
      </div>
    </div>
  </div>
  <div class="flex flex-col items-stretch h-full w-full relative overflow-hidden">
      <span
        class="absolute top-0 bottom-0 left-[0px] w-[5px] z-50 cursor-col-resize"
        onmousedown={() => {startResize('leftnav')}}
      >
        &nbsp;
      </span>
    <slot/>
  </div>
</div>


<style lang="scss">
</style>
