<script lang="ts">
import Button from '../common/_button.svelte'
import {ValidationError, invokeOnEnter, post} from "../common/form";
import ErrorPanel from '../common/form/_error_panel.svelte';
import {onMount} from "svelte";

export let redirectPath: string

let usernameInput: HTMLInputElement
let isLoading = false
let errors: string[] = []
let form = {
  username: '',
  password: ''
}

async function submit(): Promise<void> {
  isLoading = true
  try {
    const _json = await post('/login', form)

    window.location.href = redirectPath
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
        <span>Username</span>
        <input type="text" class="input input-accent w-full" placeholder="Username" data-test-id="email" bind:this={usernameInput} bind:value={form.username}/>
        <span>Password</span>
        <input type="password" class="input input-accent w-full" placeholder="Password" data-test-id="password"
               bind:value={form.password}/>
        <ErrorPanel {errors}/>
        <Button class="btn btn-primary" {isLoading} onClick={submit} dataTestId="submit-button">Login</Button>
        <div class="text-xs">
          Doesn't have an account or forgot password? Please contact your administrator.
        </div>
      </div>
    </div>
  </div>
</div>


<style lang="scss">
</style>
