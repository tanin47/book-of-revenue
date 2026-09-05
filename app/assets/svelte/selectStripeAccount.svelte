<script lang="ts">
import StripeAccountSelectorDialog from "./dashboard/_stripe_account_selector_dialog.svelte";
import {onMount} from "svelte";

let dialog: StripeAccountSelectorDialog;

onMount(() => {dialog.open()})
</script>

<StripeAccountSelectorDialog bind:this={dialog} disableClose />
