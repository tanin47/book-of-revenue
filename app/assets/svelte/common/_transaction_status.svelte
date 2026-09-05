<script lang="ts">
  export let value: any;

  let klass = ''
  $: {
    if (value === 'Paid' || value === 'Transacted') {
      klass = 'text-green-800'
    } else if (value === 'Voided' || value === 'Uncollectible' || value === 'Unpaid') {
      klass = 'text-red-800'
    } else if (value === 'Draft' || value === 'Undetermined') {
      klass = 'text-gray-800'
    } else if (value === 'Error') {
      klass = 'text-red-800 font-bold'
    }
  }
</script>

<span class={klass}>{value}</span>
