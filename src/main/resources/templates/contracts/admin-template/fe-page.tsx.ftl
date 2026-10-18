<#assign x = module.of(entity.db)>
<#assign X = x.className>
<#assign v = x.var>
<#assign guarded = module.permission?has_content>
'use client'

// [=generatedNote]
<#assign editable = x.create || x.update>
<#if editable>
import { useState } from 'react'
</#if>
import Box from '@mui/material/Box'
<#if x.create>
import Button from '@mui/material/Button'
</#if>
import Card from '@mui/material/Card'
<#if x.update || x.delete>
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
</#if>
<#if x.create>
import AddIcon from '@mui/icons-material/Add'
</#if>
<#if x.delete>
import DeleteOutlined from '@mui/icons-material/DeleteOutlined'
</#if>
<#if x.update>
import EditOutlined from '@mui/icons-material/EditOutlined'
</#if>
import { useTranslations } from 'next-intl'
<#if x.delete>
import { ApiError } from '@/lib/api/client'
</#if>
<#if guarded>
import { usePermissions } from '@/lib/auth/permissions'
</#if>
<#if x.listColumns?filter(c -> c.render?contains("format."))?has_content>
import { useFormat } from '@/lib/format'
</#if>
import { useReference } from '@/lib/reference'
import { AppPage } from '@/components/app-page'
<#if x.delete>
import { useConfirm } from '@/components/confirm-provider'
</#if>
import { DataTable } from '@/components/data-table'
import type { Column } from '@/components/data-table'
<#if x.searchPaths?has_content>
import { SearchInput } from '@/components/search-input'
</#if>
<#if x.delete>
import { useToast } from '@/components/toast-provider'
</#if>
import { useListParams } from '@/components/use-list-params'
import { <#if x.delete>useDelete[=X], </#if>use[=X]List } from './api'
<#if editable>
import { [=X]FormModal } from './[=x.kebab]-form-modal'
</#if>
import type { [=X] } from './types'

/** Hides a text visually while keeping it for screen readers. */
const srOnly = {
  border: 0,
  clip: 'rect(0 0 0 0)',
  height: '1px',
  margin: '-1px',
  overflow: 'hidden',
  padding: 0,
  position: 'absolute',
  whiteSpace: 'nowrap',
  width: '1px',
} as const

export function [=X]Page() {
  const t = useTranslations('[=module.name]')
  const tCommon = useTranslations('common')
<#if x.listColumns?filter(c -> c.render?contains("format."))?has_content>
  const format = useFormat()
</#if>
<#if x.delete>
  const toast = useToast()
  const confirm = useConfirm()
</#if>
  const reference = useReference()
<#if guarded>
  const { can } = usePermissions()
  const canManage = can('[=module.permission]')
<#else>
  const canManage = true
</#if>
  const { values, page, update } = useListParams([<#if x.searchPaths?has_content>'q'</#if>] as const, { sort: '[=x.defaultSort],asc' })
  const list = use[=X]List({ ...values, page, size: reference.ui.pageSize })
<#if x.delete>
  const remove = useDelete[=X]()
</#if>
<#if editable>
  const [editing, setEditing] = useState<[=X] | 'new' | null>(null)
</#if>
<#if x.delete>

  const onDelete = async (row: [=X]) => {
    const ok = await confirm({
      title: t('[=v].deleteTitle'),
      message: tCommon('deleteConfirmText'),
      confirmLabel: tCommon('delete'),
      danger: true,
    })
    if (!ok) return
    remove.mutate(row.[=x.idName], {
      onSuccess: () => toast.success(tCommon('deleted')),
      onError: error => toast.error(error instanceof ApiError ? error.message : tCommon('unknownError')),
    })
  }
</#if>

  const columns: Column<[=X]>[] = [
<#list x.listColumns as c>
    {
      key: '[=c.name]',
      header: t('[=v].fields.[=c.name]'),
<#if c.sortKey?has_content>
      sortKey: '[=c.sortKey]',
</#if>
<#if c.right>
      align: 'right',
</#if>
      nowrap: true,
      render: r => [=c.render],
    },
</#list>
<#if x.update || x.delete>
    ...(canManage
      ? [
          {
            key: 'actions',
            header: <Box component="span" sx={srOnly}>{tCommon('actions')}</Box>,
            align: 'right' as const,
            nowrap: true,
            render: (r: [=X]) => (
              <>
<#if x.update>
                <Tooltip title={tCommon('edit')}>
                  <IconButton size="small" aria-label={tCommon('edit')} onClick={() => setEditing(r)}>
                    <EditOutlined fontSize="small" />
                  </IconButton>
                </Tooltip>
</#if>
<#if x.delete>
                <Tooltip title={tCommon('delete')}>
                  <span>
                    <IconButton
                      size="small"
                      color="error"
                      aria-label={tCommon('delete')}
                      disabled={!r.deletable}
                      onClick={() => onDelete(r)}
                    >
                      <DeleteOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
</#if>
              </>
            ),
          },
        ]
      : []),
</#if>
  ]

  return (
    <AppPage title={t('[=v].title')}>
      <Card>
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center', p: 2, borderBottom: 1, borderColor: 'divider' }}>
<#if x.searchPaths?has_content>
          <SearchInput value={values.q} onChange={q => update({ q })} placeholder={t('[=v].search')} />
</#if>
<#if x.create>
          {canManage && (
            <Box sx={{ ml: 'auto' }}>
              <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>
                {t('[=v].new')}
              </Button>
            </Box>
          )}
</#if>
        </Box>
        <DataTable
          columns={columns}
          page={list.data}
          rowKey={r => r.[=x.idName]}
          isLoading={list.isFetching}
          error={list.error}
          onRetry={() => list.refetch()}
          sort={values.sort}
          onSortChange={sort => update({ sort })}
          onPageChange={p => update({ page: p })}
          emptyTitle={t('[=v].empty')}
        />
      </Card>
<#if editable>
      {editing && <[=X]FormModal row={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
</#if>
    </AppPage>
  )
}
