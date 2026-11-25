<template>
          <div class="discoverer-board">
            <div class="board-header">
              <WorkspaceSectionHeading title="发现者统计" icon="PieChart" />
              <el-select v-model="discovererTimeRange" size="small" style="width: 110px;" @change="emit('change')">
                <el-option label="不限时间" value="all" />
                <el-option label="今天" value="today" />
                <el-option label="近一周" value="week" />
                <el-option label="近一月" value="month" />
                <el-option label="近一年" value="year" />
              </el-select>
            </div>
            <p class="board-scope">当前范围 · {{ discovererStats.length }} 位发现者</p>
            <table class="board-table">
              <thead>
                <tr>
                  <th class="col-name">发现者</th>
                  <th class="col-num">问题数</th>
                  <th class="col-num">核查原因有</th>
                  <th class="col-num">附件有</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in discovererStats" :key="(row.discoverer || '').trim()">
                  <td class="col-name cell-link" :title="(row.discoverer || '').trim()" role="button" tabindex="0" @keydown.enter="emit('drill', row, null)" @keydown.space.prevent="emit('drill', row, null)" @click="emit('drill', row, null)">{{ (row.discoverer || '').trim() }}</td>
                  <td class="col-num cell-link" role="button" tabindex="0" @keydown.enter="emit('drill', row, 'issueCount')" @keydown.space.prevent="emit('drill', row, 'issueCount')" @click="emit('drill', row, 'issueCount')">{{ row.issueCount }}</td>
                  <td class="col-num cell-link" role="button" tabindex="0" @keydown.enter="emit('drill', row, 'checkReasonCount')" @keydown.space.prevent="emit('drill', row, 'checkReasonCount')" @click="emit('drill', row, 'checkReasonCount')">{{ row.checkReasonCount }}</td>
                  <td class="col-num cell-link" role="button" tabindex="0" @keydown.enter="emit('drill', row, 'attachmentCount')" @keydown.space.prevent="emit('drill', row, 'attachmentCount')" @click="emit('drill', row, 'attachmentCount')">{{ row.attachmentCount }}</td>
                </tr>
                <tr v-if="discovererStats.length === 0">
                  <td colspan="4" class="board-empty">暂无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
</template>
<script setup>
import WorkspaceSectionHeading from '@/components/workspace/WorkspaceSectionHeading.vue'
const discovererTimeRange=defineModel('range',{type:String,required:true})
defineProps({discovererStats:{type:Array,default:()=>[]}})
const emit=defineEmits(['change','drill'])
</script>
<style scoped>
.discoverer-board { height:100%; min-height:240px; padding:20px; border:1px solid var(--ui-border); border-radius:12px; background:var(--ui-surface); box-sizing:border-box; } .board-header { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:16px; } .board-title { color:var(--ui-heading); font-size:14px; font-weight:600; } .board-table { width:100%; border-collapse:collapse; font-size:12px; } th { padding:10px 8px; color:var(--ui-muted); background:var(--ui-page); font-weight:500; text-align:left; } td { padding:10px 8px; color:var(--ui-text); border-bottom:1px solid var(--ui-border-soft); } .col-num { text-align:right; } .cell-link { cursor:pointer; color:var(--ui-primary); } .cell-link:hover { background:var(--ui-primary-soft); text-decoration:underline; } .board-empty { text-align:center; color:var(--ui-subtle); }
.board-scope { margin:0 0 10px; color:var(--ui-subtle); font-size:11px; }.board-table td:nth-child(3) { color:var(--ui-success); }.board-table td:nth-child(4) { color:var(--ui-subtle); }.cell-link:focus-visible { outline:2px solid var(--ui-primary); outline-offset:-2px; }
</style>
