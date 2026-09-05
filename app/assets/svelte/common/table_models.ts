import type {Component, SvelteComponent} from "svelte";

export type ComputeLinkFn = ((
  value: any,
  dataRow: any[],
  dataColumnIndexById: {[key: string]: number},
  columnId: string,
  params: any
) => string | null)

export interface ColumnSetting {
  isRegex?: boolean;
  maxCharacterLength?: number | null;
  computeLink?: ComputeLinkFn | null;
  primaryKey?: boolean
  hidden?: boolean
  component?: Component<any, any> | null
}

export interface FetchedColumn {
  id: string;
  type: string;
}

export interface Column extends FetchedColumn {
  name: string;
  maxCharacterLength: number;
  hidden?: boolean | null
}

export type SortDirection = 'Asc' | 'Desc';

export interface Sort {
  columnId: string;
  direction: SortDirection;
}

export interface TableParams {
  columns: string[]
  sorts: Sort[]
}

export interface FetchMoreResult {
  rows: any[][]
}

export interface FetchResult extends FetchMoreResult {
  totalNumberOfRows: number
  columns: FetchedColumn[]
}

export function parseSortParam(sortParam: string | null): Sort[] {
  const sorts: Sort[] = []
  if (sortParam) {
    for (const sort of sortParam.split(',')) {
      const comps = sort.split('.')
      sorts.push({
        columnId: comps[0],
        direction: comps[1].toLowerCase() === 'desc' ? 'Desc' : 'Asc'
      })
    }
  }
  return sorts
}

export function makeSortParam(sorts: Sort[]): string {
  const ps: string[] = []

  for (const sort of sorts) {
    ps.push(`${sort.columnId}.${sort.direction}`)
  }

  return ps.join(',')
}

export interface ColumnProperty {
  id: string
  name: string
  dependsOn?: string[] | null
  highlighted?: boolean
}

export function expandColumns(columns: string[], columnProperties: ColumnProperty[]): string[] {
  const columnById = new Map(columnProperties.map((column) => [column.id, column]))
  const expanded = [...columns]

  const queue = [...columns]
  while (queue.length > 0) {
    const id = queue.shift()!
    const dependsOn = columnById.get(id)?.dependsOn ?? []
    for (const dependency of dependsOn) {
      if (!expanded.includes(dependency)) {
        expanded.push(dependency)
        queue.push(dependency)
      }
    }
  }

  return expanded
}

export interface ColumnSelectionItem {
  id: string
  forceChecked?: boolean
  rank?: number
}

export type GroupBy = 'Summary' | 'Product' | 'Customer' | 'Transaction' | 'LineItem' | null

export interface ColumnGroupBy {
  groupBy: GroupBy
  columns: ColumnSelectionItem[]
}

export function inPlaceSortColumnSelectionItems(columns: ColumnSelectionItem[]): ColumnSelectionItem[] {
  columns.sort((a, b) => {
    if (a.forceChecked === b.forceChecked){
      const aRank = a.rank ?? 10000
      const bRank = b.rank ?? 10000
      if (aRank === bRank) {
        return a.id.localeCompare(b.id)
      } else if (aRank <= bRank) {
        return -1
      } else {
        return 1
      }
    } else {
      return a.forceChecked ? -1 : 1
    }
  })
  return columns
}

export function inPlaceSortColumnGroupBys(columnGroupBys: ColumnGroupBy[]): ColumnGroupBy[] {
  columnGroupBys.forEach(c => inPlaceSortColumnSelectionItems(c.columns))
  return columnGroupBys
}

export function makeColumnGroupBys(groupBys: GroupBy[], baseColumns: ColumnSelectionItem[]): ColumnGroupBy[] {
  return inPlaceSortColumnGroupBys(groupBys.map(groupBy => {
    switch (groupBy) {
      case 'Summary':
        return makeSummaryColumnGroupBy(baseColumns)
      case 'Product':
        return makeProductColumnGroupBy(baseColumns)
      case 'Customer':
        return makeCustomerColumnGroupBy(baseColumns)
      case 'Transaction':
        return makeTransactionColumnGroupBy(baseColumns)
      case 'LineItem':
        return makeLineItemColumnGroupBy(baseColumns)
      case null:
        return makeNullColumnGroupBy(baseColumns)
      default:
        throw new Error(`Unknown groupBy: ${groupBy}`)
     }
  }))
}

export function makeSummaryColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: 'Summary',
    columns: inPlaceSortColumnSelectionItems(baseColumns)
  }
}

export function makeProductColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: 'Product',
    columns: inPlaceSortColumnSelectionItems(baseColumns.concat([{id: 'ProductName', forceChecked: true}]))
  }
}

export function makeCustomerColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: 'Customer',
    columns: inPlaceSortColumnSelectionItems(baseColumns.concat([
      {id: 'CustomerName', forceChecked: true},
      {id: 'CustomerEmail'}
    ]))
  }
}


export function makeTransactionColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: 'Transaction',
    columns: inPlaceSortColumnSelectionItems(baseColumns.concat([
      {id: 'RevRecTransactionTitle', forceChecked: true},
      {id: 'CustomerName', forceChecked: true},
      {id: 'CustomerEmail'},
      {id: 'InvoiceNumber'}
    ]))
  }
}

export function makeLineItemColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: 'LineItem',
    columns: inPlaceSortColumnSelectionItems(baseColumns.concat([
      {id: 'InvoiceNumber'},
      {id: 'InvoiceLineItemDescription', forceChecked: true},
      {id: 'InvoiceLineItemStartedAt'},
      {id: 'InvoiceLineItemEndedAt'},
      {id: 'RevRecTransactionTitle', forceChecked: true},
      {id: 'CustomerName', forceChecked: true},
      {id: 'ProductName'},
    ]))
  }
}

export function makeNullColumnGroupBy(baseColumns: ColumnSelectionItem[]): ColumnGroupBy {
  return {
    groupBy: null,
    columns: inPlaceSortColumnSelectionItems(baseColumns.concat([
      {id: 'InvoiceNumber'},
      {id: 'InvoiceLineItemDescription', forceChecked: true},
      {id: 'InvoiceLineItemStartedAt'},
      {id: 'InvoiceLineItemEndedAt'},
      {id: 'RevRecTransactionTitle'},
      {id: 'CustomerName'},
      {id: 'ProductName'},
      {id: 'OccurredAt', forceChecked: true},
      {id: 'Event', forceChecked: true},
      {id: 'ReversedEvent'},
      {id: 'AttributionPeriod'},
      {id: 'PresentmentAmount'},
      {id: 'PresentmentCurrency'},
    ]))
  }
}

export function sanitizeSelectedColumns(selectedColumns: string[], possibleColumns: ColumnSelectionItem[]): string[] {
  const validColumns = new Map<string, number>(possibleColumns.map(((c, index) => [c.id, index])))
  return selectedColumns.filter(c => validColumns.has(c)).sort((a, b) => validColumns.get(a)! - validColumns.get(b)!)
}

export function sanitizeSorts(sorts: Sort[], possibleColumns: ColumnSelectionItem[]): Sort[] {
  const validColumns = new Map<string, number>(possibleColumns.map(((c, index) => [c.id, index])))
  return sorts.filter(s => validColumns.has(s.columnId))
}

export function parseColumnQueryParam(columnsParam: string | null, groupBy: string, COLUMNS: ColumnGroupBy[]): string[] {
  let columns: string[] = []
  if (columnsParam) {
    columns = columnsParam.split(',').map(a => a.trim()).filter((a) => a.length > 0)
  }
  const availableColumns = (COLUMNS.find(c => c.groupBy === groupBy)!.columns)
  availableColumns.forEach(column => {
    if (column.forceChecked && !columns.includes(column.id)) {
      columns.push(column.id)
    }
  })

  return columns
}

const NUMERICAL_VALUE_TYPES = new Set(['Amount', 'DeltaAmount', 'Number', 'Percentage'])

export function isNumericColumn(columnType: string): boolean {
  return NUMERICAL_VALUE_TYPES.has(columnType)
}
