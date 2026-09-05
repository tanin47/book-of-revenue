<script lang="ts">
import {debounce} from "./globals";

export let keyword = ''
export let onSearch = (value: string) => {}

let keywordInput: HTMLInputElement

function search() {
  onSearch(keywordInput.value)
}

function clearSearch() {
  keywordInput!.value = ''
  search()
}
</script>

<div class="flex items-center gap-1 text-sm">
  <i class="ph-duotone ph-magnifying-glass opacity-70"></i>
  <input
    bind:this={keywordInput}
    type="text"
    placeholder="Search"
    class="py-0 w-40 bg-transparent rounded-none focus:outline-none placeholder:opacity-50"
    bind:value={keyword}
    oninput={debounce(search, 500)}
  />
  {#if keyword.length > 0}
    <i class="ph ph-x cursor-pointer opacity-70 hover:opacity-100" title="Clear the search" aria-label="Clear the search" onclick={clearSearch}></i>
  {/if}
</div>
