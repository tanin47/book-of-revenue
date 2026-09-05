<script lang="ts">
import Button from '../common/_button.svelte'
import {ValidationError, invokeOnEnter, post} from "../common/form";
import ErrorPanel from '../common/form/_error_panel.svelte';
import {onMount} from "svelte";

let usernameInput: HTMLInputElement
let isLoading = false
let errors: string[] = []
let form = {
  username: '',
  password: '',
  stripeApiKey: '',
}

async function submit(): Promise<void> {
  isLoading = true
  try {
    const _json = await post('/register', form)

    window.location.href = '/'
  } catch (e) {
    isLoading = false
    errors = (e as ValidationError).messages
  }
}

onMount(() => {
  usernameInput.focus()
})
</script>

<div class="hero bg-base-200 min-h-screen">
  <div class="hero-content flex-col justify-center items-center">
    <div class="card bg-base-100 min-w-[400px] w-full max-w-sm shrink-0 shadow-2xl">
      <div class="card-body flex flex-col gap-4" onkeydown={invokeOnEnter(submit)}>
        <h1 class="card-title">Setting up</h1>
        <span class="label text-accent">Username</span>
        <input type="email" class="input input-accent w-full" placeholder="Username" data-test-id="username" bind:this={usernameInput} bind:value={form.username}/>
        <span class="label text-accent">Password</span>
        <input type="password" class="input input-accent w-full" placeholder="Password" data-test-id="password" bind:value={form.password}/>
        <span class="label text-accent">Stripe API key</span>
        <input type="text" class="input input-accent w-full" placeholder="Stripe API key" data-test-id="stripeApiKey" bind:value={form.stripeApiKey}/>
        <div class="text-xs leading-relaxed">
          Since this is the first time you set up, you must provide a Stripe API key to connect to your Stripe account.
        </div>
        <div class="text-xs leading-relaxed">
          It can be a live mode or test mode API key. You can later add more API keys in the Settings page.
        </div>
        <ErrorPanel {errors}/>
        <Button class="btn btn-primary" {isLoading} onClick={submit} dataTestId="submit-button">Register</Button>
        <div class="text-xs leading-relaxed">
          After registering, adding more users must be done by you via the dashboard.
        </div>
      </div>
    </div>
  </div>
</div>


<style lang="scss">
</style>
