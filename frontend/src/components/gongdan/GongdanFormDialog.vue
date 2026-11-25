<template>
      <el-dialog class="workspace-theme gongdan-form-dialog" v-model="visible" :title="form.id ? '编辑工单' : '新建工单'" :width="width">
        <el-form :model="form" label-width="120px">
          <el-divider content-position="left">工单信息</el-divider>
          <el-form-item label="工单编号">
            <el-input v-model="form.gongdanId" placeholder="留空则自动生成" />
          </el-form-item>
          <el-form-item label="所属系统">
            <el-select v-model="form.systemId" placeholder="请选择系统" clearable style="width: 100%;" @change="emit('system-change', $event)">
              <el-option v-for="item in systemOptions" :key="item.id" :label="item.systemName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="工单标题">
            <el-input v-model="form.gongdanTitle" maxlength="200" show-word-limit placeholder="如：【指标体系平台】XX指标异常告警" />
          </el-form-item>
          <el-form-item label="事件描述">
            <el-input v-model="form.gongdanContent" type="textarea" :rows="4" placeholder="告警/问题的详细描述，将作为 BOMC 工单的 com_desc 与告警内容" />
          </el-form-item>
          <el-form-item label="工单类型">
            <el-select v-model="form.gongdanType">
              <el-option label="自动" value="自动" />
              <el-option label="手动" value="手动" />
            </el-select>
          </el-form-item>

          <el-divider content-position="left">告警信息</el-divider>
          <el-form-item label="告警对象">
            <el-input v-model="form.alarmObject" placeholder="如：CRM系统 - 查询类 - 平均响应时长" />
          </el-form-item>
          <el-form-item label="告警级别">
            <el-select v-model="form.alarmLevel" style="width: 100%;">
              <el-option v-for="item in alarmLevelOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.alarmPriority" clearable placeholder="留空则按告警级别自动推导" style="width: 100%;">
              <el-option label="低" value="低" />
              <el-option label="中" value="中" />
              <el-option label="高" value="高" />
            </el-select>
          </el-form-item>
          <el-form-item label="影响度">
            <el-select v-model="form.alarmImpact" clearable placeholder="留空则按告警级别自动推导" style="width: 100%;">
              <el-option label="一般" value="一般" />
              <el-option label="重大" value="重大" />
              <el-option label="严重" value="严重" />
              <el-option label="无" value="无" />
            </el-select>
          </el-form-item>
          <el-form-item label="告警次数">
            <el-input-number v-model="form.alarmCount" :min="1" :max="999999" controls-position="right" style="width: 100%;" />
          </el-form-item>
          <el-form-item label="首次发生时间">
            <el-date-picker v-model="form.alarmFirstTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="默认取工单时间" style="width: 100%;" />
          </el-form-item>
          <el-form-item label="末次发生时间">
            <el-date-picker v-model="form.alarmLastTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="默认取工单时间" style="width: 100%;" />
          </el-form-item>

          <el-divider content-position="left">责任与处理</el-divider>
          <el-form-item label="主要责任人">
            <el-input v-model="form.responsiblePerson" placeholder="留空使用平台默认责任人" />
          </el-form-item>
          <el-form-item label="次要责任人">
            <el-input v-model="form.secondResponsible" placeholder="留空使用平台默认责任人" />
          </el-form-item>
          <el-form-item label="BOMC处理人">
            <el-input v-model="form.executeUser" placeholder="BOMC 系统人员ID，留空使用平台默认值" />
          </el-form-item>
        </el-form>
        <div class="dialog-tip">保存后系统会自动把工单推送到省 BOMC 创建工单，并回填 BOMC 工单号。</div>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="emit('save')">确定</el-button>
      </template>
    </el-dialog>
</template>
<script setup>
const visible=defineModel({type:Boolean,default:false})
const form=defineModel('form',{type:Object,required:true})
defineProps({width:[Number,String],systemOptions:{type:Array,default:()=>[]},alarmLevelOptions:{type:Array,default:()=>[]}})
const emit=defineEmits(['save','system-change'])
</script>
<style scoped>.dialog-tip { padding:12px 16px; border:1px solid var(--ui-primary-border); border-radius:8px; background:var(--ui-primary-soft); color:var(--ui-primary-strong); font-size:12px; line-height:1.8; }</style>
