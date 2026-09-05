<script lang="ts" context="module">
  import {CURRENT_STRIPE_ACCOUNT} from "./globals";
  import TransactionStatus from "./_transaction_status.svelte"

  export const DEFAULT_COLUMN_RENDERING_SETTINGS: {[key: string]: ColumnSetting} = {
    RevRecTransactionId: {computeLink: (value) => `/customer/transaction/${value}`},
    RevRecTransactionTitle: {computeLink: (value, data, dataIndexById) => `/customer/transaction/${data[dataIndexById.RevRecTransactionId]}`, maxCharacterLength: 24},
    ProductId: {computeLink: (value) => value ? `https://dashboard.stripe.com/${CURRENT_STRIPE_ACCOUNT!.stripeAccount.id}/products/${value}` : null},
    ProductName: {computeLink: (value, data, dataIndexById) => data[dataIndexById.ProductId] ? `https://dashboard.stripe.com/${CURRENT_STRIPE_ACCOUNT!.stripeAccount.id}/products/${data[dataIndexById.ProductId]}` : null},
    CustomerId: {computeLink: (value) => `/customer/${value ?? 'empty'}`},
    CustomerName: {computeLink: (value, data, dataIndexById) => `/customer/${data[dataIndexById.CustomerId] ?? 'empty'}`},
    CustomerEmail: {computeLink: (value, data, dataIndexById) => `/customer/${data[dataIndexById.CustomerId] ?? 'empty'}`},
    InvoiceId: {computeLink: (value) => value ? `/customer/transaction/${value}` : null},
    InvoiceNumber: {computeLink: (value, data, dataIndexById) => data[dataIndexById.InvoiceId] ? `/customer/transaction/${data[dataIndexById.InvoiceId]}` : null},
    InvoiceLineItemId: {computeLink: (value, data, dataIndexById) => data[dataIndexById.InvoiceId] ? `/customer/line-item/${value}` : null},
    InvoiceLineItemDescription: { computeLink: (value, data, dataIndexById) => data[dataIndexById.InvoiceLineItemId] ? `/customer/line-item/${data[dataIndexById.InvoiceLineItemId]}` : null},
    TransactionStatus: {component: TransactionStatus}
  }

  export const COLUMN_PROPERTIES: ColumnProperty[] = [
    {id: 'Account', name: 'Account'},
    {id: 'AccountingPeriod', name: 'Period'},
    {id: 'Amount', name: 'Amount'},
    {id: 'AttributionPeriod', name: 'Attributed period'},
    {id: 'BookedAccountingPeriod', name: 'Booked'},
    {id: 'Category', name: 'Category'},
    {id: 'RevRecTransactionId', name: 'Transaction ID'},
    {id: 'RevRecTransactionTitle', name: 'Transaction', dependsOn: ['RevRecTransactionId']},
    {id: 'Credit', name: 'Credit'},
    {id: 'CreditNotes', name: 'Credit notes'},
    {id: 'Currency', name: 'Currency'},
    {id: 'CustomerEmail', name: 'Customer email', dependsOn: ['CustomerId']},
    {id: 'CustomerId', name: 'Customer ID'},
    {id: 'CustomerName', name: 'Customer', dependsOn: ['CustomerId']},
    {id: 'Date', name: 'Date'},
    {id: 'Days120', name: '91 to 120 days'},
    {id: 'Days120Plus', name: 'Over 120 days'},
    {id: 'Days30', name: '1 to 30 days'},
    {id: 'Days60', name: '31 to 60 days'},
    {id: 'Days90', name: '61 to 90 days'},
    {id: 'Debit', name: 'Debit'},
    {id: 'Disputes', name: 'Disputes'},
    {id: 'Event', name: 'Event'},
    {id: 'GrossRevenue', name: 'Gross revenue'},
    {id: 'InvoiceId', name: 'Invoice ID'},
    {id: 'InvoiceLineItemDescription', name: 'Invoice line item', dependsOn: ['InvoiceLineItemId']},
    {id: 'InvoiceLineItemEndedAt', name: 'Invoice line item end'},
    {id: 'InvoiceLineItemId', name: 'Invoice line item ID'},
    {id: 'InvoiceLineItemStartedAt', name: 'Invoice line item start'},
    {id: 'InvoiceNumber', name: 'Invoice number', dependsOn: ['InvoiceId']},
    {id: 'NetChange', name: 'Net change'},
    {id: 'NetIncome', name: 'Net income'},
    {id: 'NetRevenue', name: 'Net revenue'},
    {id: 'NotDue', name: 'Not due'},
    {id: 'OccurredAt', name: 'Booked at'},
    {id: 'PresentmentAmount', name: 'Presentment amount'},
    {id: 'PresentmentCurrency', name: 'Presentment currency'},
    {id: 'ProductId', name: 'Product ID'},
    {id: 'ProductName', name: 'Product', dependsOn: ['ProductId']},
    {id: 'Refunds', name: 'Refunds'},
    {id: 'ReversedEvent', name: 'Reversed event'},
    {id: 'Total', name: 'Total'},
    {id: 'StartingBalance', name: 'Starting balance'},
    {id: 'EndingBalance', name: 'Ending balance'},
    {id: 'Voids', name: 'Voids'},
    {id: 'TransactionStatus', name: 'Status'}
  ]
  export const COLUMN_NAMES: Record<string, string> = Object.fromEntries(COLUMN_PROPERTIES.map(c => [c.id, c.name]))
</script>
<script lang="ts">
import VirtualTable, {type Item} from './_virtual_table.svelte';
import {ValidationError} from "./form";
import TableCellValue from './_table_cell_value.svelte'
import {
  type Column,
  type ColumnProperty,
  type ColumnSetting, type ComputeLinkFn,
  expandColumns,
  type FetchMoreResult,
  type FetchResult, isNumericColumn,
  type SortDirection,
  type TableParams
} from "./table_models";

export let columnProperties: ColumnProperty[] = COLUMN_PROPERTIES
export let columnRenderingSettings: {[key: string]: ColumnSetting} = DEFAULT_COLUMN_RENDERING_SETTINGS
export let params: TableParams = {columns: [], sorts: []}
export let onFetch: (params: any, pushHistory: boolean) => Promise<FetchResult>
export let onFetchMore: (params: any, offset: number) => Promise<FetchMoreResult>
export let totalNumberOfRows = 0
export let highlightedColumnId: string | null = null
export let stickFirstNColumns: number = 0

let dataColumns: Column[] = []
let dataColumnIndexById: {[key: string]: number} = {}
let dataRows: any[][] = []

let columns: Column[] = []
let rows: Item[] = []
let computeLinks: {[key: string]: ComputeLinkFn} = {}
let scrollLeft = 0
let scrollTop = 0

let isLoading = false
let errors: string[] = []

let MIN_NUMBER_COLUMN_WIDTH = 36; // 3 characters
let numberColumnWidth: number = 0;
let columnWidths: number[] = []

// Primary-key values of the rows the user has clicked to highlight. Keying by the primary key (rather
// than the row index) keeps the highlight on the same record across sorting and searching.
let highlightedKeys = new Set<string>()

$: primaryKeyColumnIndex = dataColumns.findIndex(c => columnRenderingSettings[c.id]?.primaryKey)

// The primary-key value of a row. Falls back to the row index when no primary-key column is configured.
function getRowKey(values: any[], rowIndex: number): string {
  if (primaryKeyColumnIndex < 0) { return `#${rowIndex}` }
  return String(values[primaryKeyColumnIndex])
}

let calloutColumnIds: Set<string> = new Set()
$: calloutColumnIds = new Set(columnProperties.filter(c => c.highlighted).map(c => c.id))

function toggleHighlight(key: string) {
  if (highlightedKeys.has(key)) {
    highlightedKeys.delete(key)
  } else {
    highlightedKeys.add(key)
  }
  highlightedKeys = highlightedKeys
}


let totalWidth = 0
$: totalWidth = columnWidths.reduce((sum, width) => sum + width, numberColumnWidth)

function computeShownValues(dataRow: any[]): any[] {
  const row: any[] = []

  dataColumns.forEach((dataColumn, index) => {
    if (!dataColumn.hidden && !columnRenderingSettings[dataColumn.id]?.hidden) {
      row.push(dataRow[index])
    }
  })
  return row;
}

async function loadMore(): Promise<void> {
  if (rows.length === totalNumberOfRows) { return; } // no more items
  isLoading = true

  const modifiedParams = {
    ...params,
    columns: expandColumns(params.columns, columnProperties)
  }

  try {
    const json = await onFetchMore(modifiedParams, rows.length)

    for (const row of json.rows) {
      dataRows.push(row)
      rows.push({values: computeShownValues(row), data: row, rowHeight: 25})
    }
    rows = rows
  } catch (e) {
    console.error(e)
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
  } finally {
    isLoading = false
  }
}

export async function load(pushHistory: boolean): Promise<void> {
  isLoading = true

  const modifiedParams = {
    ...params,
    columns: expandColumns(params.columns, columnProperties)
  }

  try {
    const json = await onFetch(modifiedParams, pushHistory)

    const columnNames = Object.fromEntries(columnProperties.map(c => [c.id, c.name]))

    totalNumberOfRows = json.totalNumberOfRows
    dataColumns = json.columns.map((column) => {
      return {
        ...column,
        name: columnNames[column.id] ?? column.id,
        hidden: !params.columns.includes(column.id) && modifiedParams.columns.includes(column.id), // Hide the expanded column
        maxCharacterLength: 0
      }
    })

    columns = dataColumns.filter(c => !c.hidden && !columnRenderingSettings[c.id]?.hidden)

    dataRows = json.rows as any[][]
    rows = []
    for (const dataRow of dataRows) {
      rows.push({values: computeShownValues(dataRow), data: dataRow, rowHeight: 25})
    }

    dataColumnIndexById = {}
    for (let index=0;index<dataColumns.length;index++) {
      dataColumnIndexById[dataColumns[index].id] = index
    }

    for (const row of rows) {
      for (const index in row.values) {
        columns[index].maxCharacterLength = Math.max(columns[index].maxCharacterLength ?? 0, getCharacterCount(row.values[index], columns[index].type))
      }
    }

    for (const column of columns) {
      column.maxCharacterLength = Math.max(column.maxCharacterLength ?? 0, column.name.length + 3); // + 3 for the sort icon

      if (columnRenderingSettings[column.id]) {
        const maxCharacterLengthSetting = columnRenderingSettings[column.id].maxCharacterLength

        if (maxCharacterLengthSetting) {
          column.maxCharacterLength = Math.min(column.maxCharacterLength, maxCharacterLengthSetting)
        }
      }
    }

    computeLinks = {}
    // Process non-regex computeLink settings
    Object.entries(columnRenderingSettings).forEach(([columnId, columnSetting]) => {
      if (columnSetting.computeLink) {
        if (!columnSetting.isRegex) {
          computeLinks[columnId] = columnSetting.computeLink
        }
      }
    })
    // Process regex computeLink settings
    Object.entries(columnRenderingSettings).forEach(([columnId, columnSetting]) => {
      if (columnSetting.computeLink) {
        if (columnSetting.isRegex) {
          columns.forEach((column) => {
            if (
              !COLUMN_NAMES[column.id] && // Only dynamic column
              new RegExp(columnId).test(column.id)
            ) {
              computeLinks[column.id] = columnSetting.computeLink!
            }
          })
        }
      }
    })

    scrollTop = 0
  } catch (e) {
    console.error(e)
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
  } finally {
    isLoading = false
  }
}
async function addSort(column: string, direction: SortDirection) {
  const newSorts = [...params.sorts]
  const foundIndex = newSorts.findIndex(s => s.columnId === column)

  if (foundIndex > -1) {
    newSorts.splice(foundIndex, 1)
  }

  newSorts.push({columnId: column, direction: direction})

  params = {...params, sorts: newSorts}

  await load(true)
}

// None -> Asc -> Desc -> None
async function cycleSort(column: string) {
  const current = params.sorts.find(s => s.columnId === column)

  if (!current) {
    await addSort(column, 'Asc')
  } else if (current.direction === 'Asc') {
    await addSort(column, 'Desc')
  } else {
    await removeSort(column)
  }
}

async function removeSort(column: string) {
  const newSorts = [...params.sorts]
  const foundIndex = newSorts.findIndex(s => s.columnId === column)

  if (foundIndex > -1) {
    newSorts.splice(foundIndex, 1)
  }
  params = {...params, sorts: newSorts}
  await load(true)
}

let dummyElem: HTMLElement | null = null
let font = ''
let canvas =  document.createElement("canvas")
const context = canvas.getContext("2d");


function getCssStyle(element: HTMLElement, prop: string): string {
  return window.getComputedStyle(element, null).getPropertyValue(prop);
}

function getCanvasFont(el: HTMLElement): string {
  const fontWeight = getCssStyle(el, 'font-weight')!;
  const fontSize = getCssStyle(el, 'font-size')!;
  const fontFamily = getCssStyle(el, 'font-family')!;

  return `${fontWeight} ${fontSize} ${fontFamily}`;
}

$: if (dummyElem) {
  font = getCanvasFont(dummyElem);
  context!.font = font;
}

let isResizingColumnIndex: number | 'number_column' | null = null

function startResizeNumberColumn() {
  isResizingColumnIndex = 'number_column';
  document.body.style.userSelect = 'none';
}

function startResize(columnIndex: number) {
  isResizingColumnIndex = columnIndex;
  document.body.style.userSelect = 'none';
}

function stopResize() {
  isResizingColumnIndex = null;
  document.body.style.userSelect = '';
}

function handleResize(event: MouseEvent) {
  if (isResizingColumnIndex === 'number_column') {
    numberColumnWidth = Math.max(MIN_NUMBER_COLUMN_WIDTH, numberColumnWidth + event.movementX);
  } else if (isResizingColumnIndex !== null) {
    columnWidths[isResizingColumnIndex] = Math.max(54, columnWidths[isResizingColumnIndex] + event.movementX);
    columnWidths = columnWidths;
  }
}

function setNumberColumnWidth(value: number) {
  numberColumnWidth = value;
}

function getNumberColumnWidth() {
  return numberColumnWidth;
}

function getColumnWidth(index: number): number | undefined {
  return columnWidths[index];
}

function setColumnWidth(index: number, value: number) {
  columnWidths[index] = value;
}

function calculateColumnWidth(textLength: number) {
  let text = ''
  for (let i = 0; i < textLength; i++) {
    text += 'a'
  }

  const metrics = context!.measureText(text);
  // +8 for padding, + 1 for the right border, +12 for filter icon, +4 for gap, +14 for editing icon, +4 for gap
  return metrics.width + 8 + 1;
}

function countNumberWithCommas(orig: number, alwaysShowSign: boolean = false) {
  const num = Math.abs(orig);
  let count = 1
  if (num >= 10) count++
  if (num >= 100) count++
  if (num >= 1000) count += 2
  if (num >= 10000) count++
  if (num >= 100000) count++
  if (num >= 1000000) count += 2
  if (num >= 10000000) count++
  if (num >= 100000000) count++
  if (num >= 1000000000) count += 2

  if (orig < 0 || alwaysShowSign) count++

  return count
}

function getCharacterCount(value: any, columnType: string) {
  if (value === null || value === undefined) {
    return 2;
  } else if (columnType === 'Timestamp') {
    return 19;
  } else if (columnType === 'Period') {
    return 7;
  } else if (columnType === 'Date') {
    return 10;
  } else if (columnType === 'Amount') {
    return countNumberWithCommas(value as number / 100) + 3; // Add .00
  } else if (columnType === 'DeltaAmount') {
    return countNumberWithCommas(value as number / 100, true) + 3; // Add .00
  } else if (columnType === 'Percentage') {
    return countNumberWithCommas(value as number) + 4; // Add .00%
  } else {
    return ('' + value).length;
  }
}

$: {
  for (let index = 0; index < columns.length; index++) {
    const column = columns[index];

    if (!getColumnWidth(index)) {
      setColumnWidth(index, calculateColumnWidth(column.maxCharacterLength));
    }
  }
  columnWidths.length = columns.length

  const numberColumnWidth = getNumberColumnWidth()
  setNumberColumnWidth(numberColumnWidth === 0 ? MIN_NUMBER_COLUMN_WIDTH : numberColumnWidth)
}

</script>

<svelte:window
  on:mousemove={handleResize}
  on:mouseup={stopResize}
/>
<div class="font-mono text-xs hidden" bind:this={dummyElem}></div>

{#if errors.length > 0}
  <div class="grow flex flex-col items-center justify-center gap-2 text-primary">
    <i class="ph-duotone ph-warning-circle text-4xl text-error"></i>
    <p class="text-sm text-error font-bold">An error has occurred.</p>
  </div>
{:else if columns.length > 0}
  {#if rows.length === 0 && !isLoading}
    <div class="grow flex flex-col items-center justify-center gap-2 text-primary opacity-60">
      <i class="ph-duotone ph-tray text-4xl"></i>
      <p class="text-sm">There's nothing to show.</p>
    </div>
  {:else}
    <VirtualTable
      let:item
      let:index={rowIndex}
      items={rows}
      onBottomReached={() => {
        void loadMore()
      }}
      initialScrollLeft={scrollLeft}
      initialScrollTop={scrollTop}
      onScrolled={(newScrollLeft, newScrollTop) => {
        scrollLeft = newScrollLeft
        scrollTop = newScrollTop
      }}
    >
      <div
        slot="header"
        class="flex items-stretch font-mono text-xs text-primary bg-base-200"
        style="width: {totalWidth}px;min-width: {totalWidth}px;max-width: {totalWidth}px;"
      >
        <div
          class="p-1 box-border border-e border-b border-base-content overflow-hidden sticky left-0 bg-base-200 z-20"
          style="width: {numberColumnWidth}px; min-width: {numberColumnWidth}px; max-width: {numberColumnWidth}px;"
          data-test-id="sheet-view-colum-header-number"
        >&nbsp;</div>
        <div
          class="absolute top-0 bottom-0 w-[6px] cursor-col-resize z-50"
          style="left: {numberColumnWidth-3}px"
          onmousedown={() => {startResizeNumberColumn()}}
        ></div>
        {#each columns as column, colIndex (column.id)}
          {@const cumulativeWidth = columnWidths.slice(0, colIndex + 1).reduce((sum, width) => sum + width, numberColumnWidth)}
          {@const sort = params.sorts.find(s => s.columnId === column.id)}
          <!-- the resizing bar has to be outside in order to be on top of a border -->
          <div
            class="absolute top-0 bottom-0 w-[6px] cursor-col-resize z-50"
            style="left: {cumulativeWidth-3}px"
            onmousedown={() => {startResize(colIndex)}}
          ></div>
          <div
            class="
              p-1 box-border border-e border-b border-base-content flex items-center justify-between gap-1  bg-base-200
              {colIndex < stickFirstNColumns ? 'sticky z-10' : 'z-0'}
              {calloutColumnIds.has(column.id) ? '!bg-accent/30' : ''}
            "
            style="
              width: {columnWidths[colIndex]}px;
              min-width: {columnWidths[colIndex]}px;
              max-width: {columnWidths[colIndex]}px;
              {colIndex < stickFirstNColumns ? `left: ${cumulativeWidth - columnWidths[colIndex]}px;` : ''}
            "
            class:bg-base-300={sort?.direction === 'Asc' || sort?.direction === 'Desc'}
            class:bg-lime-300={highlightedColumnId === column.id}
            data-test-id="sheet-view-column-header"
            data-test-value={columns[colIndex].id}
          >
            <div
              class="grow overflow-hidden text-ellipsis whitespace-nowrap"
              class:text-right={isNumericColumn(column.type)}
            >{column.name}</div>
            <div
              class="flex items-center gap-1 cursor-pointer"
              title="Sort by {column.name}"
              role="button"
              tabindex="0"
              onclick={() => {void cycleSort(column.id)}}
            >
              {#if sort && sort.direction === 'Asc'}
                <i class="ph-duotone ph-caret-up" data-test-id="sort-button" data-test-value="Asc"></i>
              {:else if sort && sort.direction === 'Desc'}
                <i class="ph-duotone ph-caret-down" data-test-id="sort-button" data-test-value="Desc"></i>
              {:else}
                <i class="ph-duotone ph-caret-up-down opacity-30" data-test-id="sort-button" data-test-value="none"></i>
              {/if}
            </div>
          </div>
        {/each}
      </div>
      {@const rowKey = getRowKey(item.data, rowIndex)}
      {@const highlighted = highlightedKeys.has(rowKey)}
      <div
        class="group inline-flex items-stretch font-mono text-xs text-primary box-border border-b border-base-content {highlighted ? 'bg-warning/40 hover:bg-warning/50' : 'odd:bg-base-100 even:bg-base-200/40 hover:bg-base-300'}"
        style="height: {item.rowHeight}px;min-height: {item.rowHeight}px;max-height: {item.rowHeight}px;"
        data-test-id="sheet-view-row"
        data-test-highlighted={highlighted}
        role="button"
        tabindex="0"
        aria-pressed={highlighted}
        onclick={() => {toggleHighlight(rowKey)}}
      >
        <div
          class="p-1 box-border border-e border-base-content flex items-baseline justify-end sticky left-0 z-10 {highlighted ? 'bg-warning group-hover:!bg-warning': 'bg-base-200 group-hover:bg-base-300'}"
          style="width: {numberColumnWidth}px; min-width: {numberColumnWidth}px; max-width: {numberColumnWidth}px;"
          data-test-id="sheet-view-number-col"
        >
          <div class="overflow-hidden text-ellipsis text-right w-full opacity-60 group-hover:opacity-100">
            {rowIndex + 1}
          </div>
        </div>
        {#each item.values as value, colIndex (colIndex)}
          {@const cumulativeWidth = columnWidths.slice(0, colIndex + 1).reduce((sum, width) => sum + width, numberColumnWidth)}
          {@const link = computeLinks[columns[colIndex].id] ? computeLinks[columns[colIndex].id](value, item.data, dataColumnIndexById, columns[colIndex].id, params) : null}
          <div
            class="
              p-1 box-border border-e border-base-content flex items-baseline gap-1 whitespace-pre
              {highlighted ? '!bg-warning group-hover:!bg-warning': 'bg-base-100 group-hover:!bg-base-300'}
              {colIndex < stickFirstNColumns ? 'sticky z-10' : ''}
              {calloutColumnIds.has(columns[colIndex].id) ? '!bg-accent/10' : ''}
            "
            class:justify-end={isNumericColumn(columns[colIndex].type)}
            class:bg-lime-100={highlightedColumnId === columns[colIndex].id}
            style="
              width: {columnWidths[colIndex]}px;
              min-width: {columnWidths[colIndex]}px;
              max-width: {columnWidths[colIndex]}px;
              {colIndex < stickFirstNColumns ? `left: ${cumulativeWidth - columnWidths[colIndex]}px` : ''}
            "
            data-test-id="sheet-column-value"
            data-test-value={columns[colIndex].id}
          >
            {#if link}
              <a href="{link}" class="overflow-hidden text-ellipsis" target={link.startsWith('https') ? '_blank' : ''}>
                <TableCellValue value={value} column={columns[colIndex]} isLink component={columnRenderingSettings[columns[colIndex].id]?.component ?? null} />
              </a>
            {:else}
              <TableCellValue {value} column={columns[colIndex]} component={columnRenderingSettings[columns[colIndex].id]?.component ?? null} />
            {/if}
          </div>
        {/each}
      </div>
    </VirtualTable>
  {/if}
{/if}
