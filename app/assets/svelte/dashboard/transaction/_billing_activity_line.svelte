<script context="module" lang="ts">
  export interface RelatedId {
    id: string;
    url: string | null;
  }
</script>
<script lang="ts">
import {formatDateTime} from "../../common/globals";

export let timestamp: number;
export let title: string;
export let relatedId: RelatedId | null;

export let titleColorClass: string | null;

</script>

<div class="flex flex-col gap-2 min-w-0 text-xs">
  <span class="text-base-content font-mono whitespace-nowrap">{formatDateTime(timestamp)}</span>
  <span class="leading-snug {titleColorClass ?? ''}">{title}</span>
  {#if relatedId}
    {#if relatedId.url}
      <a href={relatedId.url} class="link" target="_blank">{relatedId.id}</a>
    {:else}
      <span>{relatedId.id}</span>
    {/if}
  {/if}
</div>
