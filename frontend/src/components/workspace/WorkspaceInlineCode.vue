<template><code class="workspace-inline-code" :class="{ 'is-empty': value == null || value === '' }" :title="value == null ? emptyText : String(value)">{{ value == null || value === '' ? emptyText : value }}</code></template>
<script setup>
defineProps({value:[String,Number],emptyText:{type:String,default:'-'}})
</script>
<style scoped>.workspace-inline-code { display:inline-block; max-width:100%; box-sizing:border-box; padding:2px 7px; border:1px solid var(--ui-border-soft); border-radius:5px; background:var(--ui-page); color:var(--ui-primary-strong); font-family:Consolas,monospace; font-size:11px; line-height:1.8; overflow-wrap:anywhere; vertical-align:middle; } .is-empty { color:var(--ui-subtle); border-color:transparent; background:transparent; }</style>
