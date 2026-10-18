<#assign x = module.of(entity.db)>
// [=generatedNote]
import { Suspense } from 'react'
import { getTranslations } from 'next-intl/server'
import { [=x.className]Page } from '@/features/[=module.name]/[=x.kebab]/[=x.kebab]-page'

export async function generateMetadata() {
  const t = await getTranslations('[=module.name]')
  return { title: t('[=x.var].title') }
}

export default function Page() {
  return (
    <Suspense>
      <[=x.className]Page />
    </Suspense>
  )
}
