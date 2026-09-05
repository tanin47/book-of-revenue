<script lang="ts">
export let value: number | null = null;
export let placeholder: string = 'Select date'
export let onSelected: ((value: number | null) => void) | null = null;

export let min: number | null = null;
export let max: number | null = null;

let innerValue: string | null = value ? new Date(value).toISOString().substring(0, 10) : null;

$: {
  innerValue = value ? new Date(value).toISOString().substring(0, 10) : null;
}

$: {
  if (innerValue) {
    onSelected?.(new Date(new Date(innerValue).toISOString()).getTime())
  } else {
    onSelected?.(null)
  }
}

</script>

<input
  type="date"
  class=" p-1 rounded-lg border-1 border-base-content focus:outline-none text-xs"
  placeholder={placeholder}
  min={min ? new Date(min).toISOString().substring(0, 10) : null}
  max={max ? new Date(max).toISOString().substring(0, 10) : null}
  bind:value={innerValue}
>
