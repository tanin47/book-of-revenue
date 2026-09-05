<script lang="ts">
  export let isLoading = false
  export let disabled = false
  export let onClick: () => void = () => {}
  export let dataTestId: string | null = null

  let klass = "btn btn-neutral"
  export {klass as class}
</script>

<button
  class={klass}
  data-test-id={dataTestId}
  disabled={isLoading || disabled}
  onclick={() => { void onClick() }}
>
  {#if isLoading}
    <span class="loading loading-spinner loading-xs"></span>
  {/if}
  <slot></slot>
</button>

<style lang="scss">
</style>
