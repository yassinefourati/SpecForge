<#assign x = module.of(entity.db)>
<#assign X = x.className>
'use client'

// [=generatedNote]
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '@/lib/api/api-provider'
import type { Page } from '@/lib/api/types'
import type { [=X], [=X]Input } from './types'

const PATH = '/api/[=x.path]'
const KEY = ['[=module.name]', '[=x.var]'] as const

export interface [=X]Filters {
<#if x.searchPaths?has_content>
  q?: string
</#if>
  page?: number
  size?: number
  sort?: string
}

const item = (id: [=x.idTs]) => `${PATH}/${encodeURIComponent(String(id))}`

export function use[=X]List(filters: [=X]Filters) {
  const api = useApi()
  return useQuery({
    queryKey: [...KEY, filters],
    queryFn: () => api.get<Page<[=X]>>(PATH, { ...filters }),
    placeholderData: previous => previous,
  })
}

export function useSave[=X]() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id?: [=x.idTs]; input: [=X]Input }) =>
      id != null ? api.put<[=X]>(item(id), input) : api.post<[=X]>(PATH, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['[=module.name]'] }),
  })
}

export function useDelete[=X]() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: [=x.idTs]) => api.delete(item(id)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['[=module.name]'] }),
  })
}
