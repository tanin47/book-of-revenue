<script lang="ts">
import {type Column, isNumericColumn} from './table_models'
import {formatAccountingPeriod, formatNumber} from "./globals";
import type {Component} from "svelte";

export let value: any;
export let column: Column;
export let component: Component<any, any> | null;
export let isLink: boolean = false

function formatTimestamp(value: number): string {
  const s = new Date(value).toISOString()
  return s.substring(0, 19).replace('T', ' ')
}

let linkClass: string = ''
$: linkClass = isLink ? 'underline decoration-dotted decoration-current': ''


</script>

{#if component}
  <svelte:component this={component} {value} />
{:else if value === null && !isNumericColumn(column.type)}
  <i class="ph ph-empty text-xs text-primary/70 pt-0.5 {linkClass}"></i>
{:else if column.type === 'Timestamp'}
  <span class="overflow-hidden text-ellipsis {linkClass}">{formatTimestamp(value)}</span>
{:else if column.type === 'Date'}
  <span class="overflow-hidden text-ellipsis {linkClass}">{new Date(value).toISOString().substring(0, 10)}</span>
{:else if column.type === 'Period'}
  <span class="overflow-hidden text-ellipsis {linkClass}">{formatAccountingPeriod(value)}</span>
{:else if column.type === 'Amount'}
  {@const sanitized = value ?? 0}
  <span class="overflow-hidden text-ellipsis tabular-nums {linkClass}" class:text-neutral={sanitized < 0} class:font-bold={sanitized !== 0} class:opacity-50={sanitized === 0}>{formatNumber(sanitized/100, 2)}</span>
{:else if column.type === 'DeltaAmount'}
  {@const sanitized = value ?? 0}
  <span
    class="overflow-hidden text-ellipsis tabular-nums {linkClass}"
    class:text-neutral={sanitized < 0}
    class:text-success={sanitized > 0}
    class:font-bold={sanitized !== 0}
    class:opacity-50={sanitized === 0}
  >{sanitized > 0 ? '+' : ''}{formatNumber(sanitized/100, 2)}</span>
{:else if column.type === 'Percentage'}
  {@const sanitized = value ?? 0}
  <span class="overflow-hidden text-ellipsis tabular-nums {linkClass}" class:text-neutral={sanitized < 0} class:font-bold={sanitized !== 0} class:opacity-50={sanitized === 0}>{formatNumber(sanitized, 2)}%</span>
{:else if column.type === 'Number'}
  {@const sanitized = value ?? 0}
  <span class="overflow-hidden text-ellipsis tabular-nums {linkClass}">{formatNumber(sanitized)}</span>
{:else}
  <span class="overflow-hidden text-ellipsis {linkClass}">{value}</span>
{/if}
