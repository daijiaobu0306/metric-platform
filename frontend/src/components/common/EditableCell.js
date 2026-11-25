import { ref, h, nextTick } from 'vue'
import { ElInput, ElInputNumber, ElSelect, ElOption } from 'element-plus'

export const EditableCell = {
  props: ['value', 'type'],
  emits: ['save'],
  setup(props, { emit }) {
    const editing = ref(false)
    const inputValue = ref(props.value)
    const inputRef = ref(null)

    const startEdit = () => {
      inputValue.value = props.value
      editing.value = true
      nextTick(() => {
        inputRef.value?.focus()
      })
    }

    const saveEdit = () => {
      editing.value = false
      const newVal = inputValue.value
      if (newVal !== props.value && newVal !== null && newVal !== undefined) {
        emit('save', newVal)
      } else if (newVal === null || newVal === undefined || newVal === '') {
        emit('save', null)
      }
    }

    const handleKeyup = (e) => {
      if (e.key === 'Enter') {
        saveEdit()
      }
    }

    return () => {
      if (editing.value) {
        return h('div', { class: 'editable-cell-edit' }, [
          props.type === 'number'
            ? h(ElInputNumber, {
                modelValue: inputValue.value,
                'onUpdate:modelValue': (val) => { inputValue.value = val },
                size: 'small',
                controls: true,
                min: 0,
                style: 'width: 100%',
                onBlur: saveEdit,
                onKeyup: handleKeyup,
                ref: inputRef
              })
            : h(ElInput, {
                modelValue: inputValue.value,
                'onUpdate:modelValue': (val) => { inputValue.value = val },
                size: 'small',
                style: 'width: 100%',
                onBlur: saveEdit,
                onKeyup: handleKeyup,
                ref: inputRef
              })
        ])
      }
      return h('span', {
        class: 'editable-cell-text',
        onClick: startEdit,
        style: 'cursor: pointer; padding: 2px 4px; border-radius: 4px; display: block;'
      }, props.value ?? '-')
    }
  }
}

// 可编辑单元格组件（选择框版本）
export const EditableCellSelect = {
  props: ['value', 'options'],
  emits: ['save'],
  setup(props, { emit }) {
    const editing = ref(false)
    const selectValue = ref(props.value)

    const startEdit = () => {
      selectValue.value = props.value
      editing.value = true
    }

    const saveEdit = () => {
      editing.value = false
      if (selectValue.value !== props.value) {
        emit('save', selectValue.value)
      }
    }

    const handleChange = (val) => {
      selectValue.value = val
      emit('save', val)
      editing.value = false
    }

    return () => {
      if (editing.value) {
        return h(ElSelect, {
          modelValue: selectValue.value,
          'onUpdate:modelValue': (val) => { selectValue.value = val },
          size: 'small',
          style: 'width: 100%',
          onChange: handleChange,
          onBlur: saveEdit
        }, () => props.options.map(opt =>
          h(ElOption, { key: opt.value, label: opt.label, value: opt.value })
        ))
      }
      const selectedOpt = props.options.find(opt => opt.value === props.value)
      return h('span', {
        class: 'editable-cell-text',
        onClick: startEdit,
        style: 'cursor: pointer; padding: 2px 4px; border-radius: 4px; display: block;'
      }, selectedOpt ? selectedOpt.label : (props.value ?? '-'))
    }
  }
}
