<#assign x = module.of(entity.db)>
<#assign X = x.className>
<#assign v = x.var>
<#assign kinds = x.form?map(f -> f.kind)>
<#assign usesText = kinds?seq_contains("text") || kinds?seq_contains("number") || kinds?seq_contains("date") || kinds?seq_contains("datetime-local") || kinds?seq_contains("time")>
<#assign usesInstant = x.form?filter(f -> f.init?contains("toLocalInput"))?has_content>
<#assign selects = x.form?filter(f -> f.kind == "select")>
<#function inputValue name>
<#list x.form as f><#if f.name == name><#return f.submit></#if></#list>
<#list x.tsRequest as p><#if p.name == name && p.type?ends_with("[]")><#return "row?." + name + " ?? []"></#if></#list>
<#return "row?." + name + " ?? null">
</#function>
'use client'

// [=generatedNote]
import { useState } from 'react'
import Button from '@mui/material/Button'
import Grid from '@mui/material/Grid'
import { useTranslations } from 'next-intl'
import { ApiError } from '@/lib/api/client'
import { useConstraints } from '@/lib/reference'
import { Modal } from '@/components/modal'
<#assign fieldImports = []>
<#if selects?has_content><#assign fieldImports = fieldImports + ["SelectField"]></#if>
<#if kinds?seq_contains("switch")><#assign fieldImports = fieldImports + ["SwitchField"]></#if>
<#if kinds?seq_contains("textarea")><#assign fieldImports = fieldImports + ["TextAreaField"]></#if>
<#if usesText><#assign fieldImports = fieldImports + ["TextField"]></#if>
<#if fieldImports?has_content>
import { [=fieldImports?join(", ")] } from '@/components/form-fields'
</#if>
import { useToast } from '@/components/toast-provider'
<#if selects?has_content && usesInstant>
import { toLocalInput, useOptions } from '../shared'
<#elseif selects?has_content>
import { useOptions } from '../shared'
<#elseif usesInstant>
import { toLocalInput } from '../shared'
</#if>
import { useSave[=X] } from './api'
import type { [=X], [=X]Input } from './types'

interface FormState {
<#list x.form as f>
  [=f.name]: [=f.stateType]
</#list>
}

export function [=X]FormModal({ row, onClose }: { row: [=X] | null; onClose: () => void }) {
  const t = useTranslations('[=module.name]')
  const tCommon = useTranslations('common')
  const field = useConstraints('[=x.formKey]')
  const toast = useToast()
  const save = useSave[=X]()
<#list selects as f>
  const [=f.name]Options = useOptions('[=f.optionsPath]', '[=f.optionsId]', '[=f.optionsLabel]')
</#list>
  const [form, setForm] = useState<FormState>({
<#list x.form as f>
    [=f.name]: [=f.init],
</#list>
  })
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {}
  const set =
    <K extends keyof FormState>(key: K) =>
    (value: FormState[K]) =>
      setForm(f => ({ ...f, [key]: value }))

  const submit = () => {
    const input: [=X]Input = {
<#list x.tsRequest as p>
      [=p.name]: [=inputValue(p.name)],
</#list>
    }
    save.mutate(
      { id: row?.[=x.idName], input },
      {
        onSuccess: () => {
          toast.success(row ? tCommon('saved') : tCommon('created'))
          onClose()
        },
        onError: error => {
          if (!(error instanceof ApiError) || Object.keys(error.fieldErrors).length === 0) {
            toast.error(error instanceof ApiError ? error.message : tCommon('unknownError'))
          }
        },
      }
    )
  }

  return (
    <Modal
      open
      title={row ? t('[=v].edit') : t('[=v].new')}
      onClose={onClose}
      busy={save.isPending}
      onSubmit={submit}
      footer={
        <>
          <Button variant="outlined" color="inherit" onClick={onClose} disabled={save.isPending}>
            {tCommon('cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>
            {save.isPending ? tCommon('saving') : tCommon('save')}
          </Button>
        </>
      }
    >
      <Grid container spacing={2}>
<#list x.form as f>
        <Grid size={{ xs: 12<#if f.kind != "textarea">, md: 6</#if> }}>
<#if f.kind == "switch">
          <SwitchField label={t('[=v].fields.[=f.name]')} checked={form.[=f.name]} onChange={set('[=f.name]')} />
<#elseif f.kind == "textarea">
          <TextAreaField
            label={t('[=v].fields.[=f.name]')}
            value={form.[=f.name]}
            onChange={set('[=f.name]')}
            required={field('[=f.name]').required}
            maxLength={field('[=f.name]').maxLength}
            error={errors.[=f.name]}
          />
<#elseif f.kind == "select">
          <SelectField
            label={t('[=v].fields.[=f.name]')}
            value={form.[=f.name]}
            onChange={set('[=f.name]')}
            required={field('[=f.name]').required}
            error={errors.[=f.name]}
          >
            <option value="">–</option>
            {([=f.name]Options.data ?? []).map(o => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </SelectField>
<#else>
          <TextField
            label={t('[=v].fields.[=f.name]')}
<#if f.kind != "text">
            type="[=f.kind]"
</#if>
            value={form.[=f.name]}
            onChange={set('[=f.name]')}
            {...field('[=f.name]')}
            error={errors.[=f.name]}
<#if f.createOnly>
            disabled={row != null}
</#if>
          />
</#if>
        </Grid>
</#list>
      </Grid>
    </Modal>
  )
}
