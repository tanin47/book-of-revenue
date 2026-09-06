<script lang="ts" context="module">
export interface DataPointValue {
  id: string
  value: number;
}
export interface DataPoint {
  label?: string | null;
  period?: number | null;
  value?: number;
  values?: DataPointValue[];
}

export type Color = 'blue' | 'green' | 'red';
</script>
<script lang="ts">
import Chart, {type ChartDataset} from 'chart.js/auto';
import type {User} from "../common/models";
import {ValidationError, post} from "../common/form";
import {onMount} from "svelte";
import annotationPlugin from 'chartjs-plugin-annotation';
import {formatAmount, formatNumber} from "../common/globals";

Chart.register(annotationPlugin);

export let mainLabel: string
export let points: DataPoint[] = []
export let color: Color = "blue"
export let valueType: 'amount' | 'percentage' | 'value'
export let currency: string = 'usd'
export let getAuditUrl: (point: DataPoint, value: DataPointValue | null) => string | null = () => null

let colorCode = '#4BC0C0'

let active: {index: number; left: number; top: number} | null = null
let hideTimer: ReturnType<typeof setTimeout> | null = null

function cancelHide() {
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
}

function scheduleHide() {
  cancelHide()
  hideTimer = setTimeout(() => { active = null }, 200)
}

function monthRange(period: number): {start: string; end: string} {
  const d = new Date(period)
  const start = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1))
  const end = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0))
  const fmt = (x: Date) => x.toISOString().substring(0, 10)
  return {start: fmt(start), end: fmt(end)}
}

function drilldownHref(period: number | null | undefined, accountId?: string): string {
  const q = new URLSearchParams()
  if (period) {
    const {start, end} = monthRange(period)
    q.append('start', start)
    q.append('end', end)
  }
  if (accountId) {
    q.append('accounts', accountId)
  }
  const s = q.toString()
  return `/debits-and-credits${s ? `?${s}` : ''}`
}

function pointTitle(p: DataPoint): string {
  if (p.label) {
    return p.label
  }
  if (p.period) {
    return new Date(p.period).toISOString().substring(0, 7)
  }
  return ''
}

function tooltipRows(p: DataPoint): {label: string; formatted: string; href: string | null}[] {
  if (!p.values) {
    return []
  }
  return p.values.map((v) => ({
    label: v.id,
    formatted: formatValue(v.value),
    href: getAuditUrl(p, v),
  }))
}

function pointTotal(p: DataPoint): number {
  if (p.values) {
    let sum = 0
    p.values.forEach((v) => sum += v.value)
    return sum
  }
  return p.value ?? 0
}

function getColorCode(color: Color) {
  switch (color) {
    case 'red': return '#FF6384'
    case 'green': return '#4BC0C0'
    case 'blue': return '#36A2EB'
    default: return '#36A2EB'
  }
}

function formatValue(value: number): string {
  if (valueType === 'amount') {
    return formatAmount(value, currency)
  } else if (valueType === 'percentage') {
    return `${formatNumber(value, 2)}%`
  } else {
    return formatNumber(value)
  }
}

$: colorCode = getColorCode(color);

let canvas: HTMLCanvasElement;
let chart: Chart | null = null;

$: if (canvas) {
  const hasMultipleAmounts = !!points.find(p => !!p.values)
  let datasets: ChartDataset[] = []

  if (!hasMultipleAmounts) {
    datasets = [
      {
        data: points.map(row => row.value!),
        backgroundColor: colorCode,
        borderWidth: 0,
        order: 1
      }
    ]
  } else {
    const bars: {[key: string]: number[]} = {}
    points.forEach(row => {
      row.values!.forEach(value => {
        bars[value.id] = Array(points.length).fill(0)
      })
    })
    points.forEach((row, index) => {
      row.values!.forEach(value => {
        bars[value.id][index] = value.value
      })
    })
    datasets = [
      {
        data: points.map(row => {
          let sum = 0
          row.values!.forEach(a => sum += a.value)
          return sum
        }),
        label: mainLabel,
        type: 'line',
        order: 0
      },
      ...Object.entries(bars).map(([id, values], index) => {
        return {
          label: id,
          data: values,
          borderWidth: 0,
          order: index + 1
        }
      })
    ]
  }
  if (chart) {
    chart.destroy()
  }
  // @ts-ignore
  chart = new Chart(
    canvas,
    {
      type: 'bar',
      data: {
        labels: points.map((row) => {
          if (row.label) {
            return row.label
          } else if (row.period) {
            return new Date(row.period).toISOString().substring(0, 7)
          } else {
            throw new Error("Either label or period must be specified.")
          }
        }),
        datasets: datasets
      },
      options: {
        events: ['mousemove', 'click', 'touchstart', 'touchmove'],
        datasets: {
          line: {
            backgroundColor: '#9966FF',
            borderColor: '#9966FF',
            borderWidth: 2,
            pointBackgroundColor: '#9966FF',
            pointBorderColor: '#9966FF',
            pointRadius: 2
          }
        },
        interaction: {
          mode: 'nearest',
          axis: 'x',
          intersect: false
        },
        maintainAspectRatio: false,
        plugins: {
          legend: {display: false},
          tooltip: {
            enabled: false,
            external: (context) => {
              const { chart, tooltip } = context;
              if (tooltip.opacity === 0) {
                scheduleHide();
                return;
              }

              cancelHide();
              active = {
                index: tooltip.dataPoints?.[0]?.dataIndex ?? 0,
                left: chart.canvas.offsetLeft + tooltip.caretX,
                top: chart.canvas.offsetTop + tooltip.caretY,
              };
            },
          },
          annotation: {
            annotations: {
              zeroLine: {
                type: 'line',
                yMin: 0,
                yMax: 0,
                borderColor: '#333', // Line color
                borderWidth: 1
              }
            }
          }
        },
        scales: {
          y: {
            grid: {display: false},
            border: {width: 2, color: '#333', z: 1},
            ticks: {
              color: '#333',
              font: {weight: 'bold'},
              maxTicksLimit: 6,
              callback: (value: any) => formatValue(value)
            },
            offset: false,
            beginAtZero: true,
            stacked: true
          },
          x: {
            grid: {display: false},
            border: {width: 2, color: '#333', z: 1},
            ticks: {color: '#333', font: {weight: 'bold'}},
            offset: true,
            stacked: true
          }
        }
      }
    }
  );
}
</script>

<div class="w-full h-[200px]" onmouseleave={() => (active = null)}>
  <canvas bind:this={canvas}></canvas>
{#if active && points[active.index]}
  {@const point = points[active.index]}
  {@const rows = tooltipRows(point)}
  {@const auditUrl = getAuditUrl(point, null)}
  <div
    class="bar-tooltip"
    style="left: {active.left}px; top: {active.top}px;"
    role="tooltip"
    onmouseenter={cancelHide}
    onmouseleave={() => (active = null)}
  >
    <div class="bar-tooltip__title">{pointTitle(point)}</div>
    {#each rows as row (row.label)}
      <div class="bar-tooltip__row">
        <span class="bar-tooltip__label">{row.label}</span>
        {#if row.href}
          <a class="bar-tooltip__value" href={row.href}>{row.formatted}</a>
        {:else}
          <span class="font-bold text-white">{row.formatted}</span>
        {/if}
      </div>
    {/each}
    {#if rows.length > 0}
      <div class="bar-tooltip__divider"></div>
    {/if}
    <div class="bar-tooltip__row bar-tooltip__row--total">
      <span class="bar-tooltip__label">{mainLabel}</span>
      {#if auditUrl}
        <a class="bar-tooltip__value" href={auditUrl}>{formatValue(pointTotal(point))}</a>
      {:else}
        <span class="font-bold text-white">{formatValue(pointTotal(point))}</span>
      {/if}
    </div>
  </div>
{/if}
</div>

<style lang="scss">
.bar-tooltip {
  position: absolute;
  z-index: 50;
  transform: translate(-50%, calc(-100% - 10px));
  min-width: 170px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #1f2937;
  color: #fff;
  font-size: 12px;
  line-height: 1.3;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
  pointer-events: auto;

  &::after {
    content: '';
    position: absolute;
    left: 50%;
    bottom: -5px;
    transform: translateX(-50%);
    border-left: 5px solid transparent;
    border-right: 5px solid transparent;
    border-top: 5px solid #1f2937;
  }

  &__title {
    margin-bottom: 6px;
    font-weight: 700;
    color: #d1d5db;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 2px 0;

    &--total {
      .bar-tooltip__label {
        font-weight: 700;
        color: #fff;
      }
    }
  }

  &__label {
    color: #d1d5db;
    white-space: nowrap;
  }

  &__value {
    color: #fff;
    font-weight: 700;
    text-decoration: underline dotted;
  }

  &__divider {
    height: 1px;
    margin: 6px 0;
    background: rgba(255, 255, 255, 0.15);
  }
}
</style>
