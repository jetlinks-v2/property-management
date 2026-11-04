import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// 仅在专用验收项目运行；凭据由进程环境传入，数据文件不包含令牌或密码。
const required = name => {
  assert(process.env[name], `Missing ${name}`)
  return process.env[name]
}
const apiBase = required('QUALITY_API_URL').replace(/\/$/, '')
const headers = {
  'Content-Type': 'application/json',
  'X-Access-Token': required('QUALITY_ACCESS_TOKEN'),
  'X-Tenant-Domain': required('QUALITY_TENANT_DOMAIN'),
  'X-Asset-Scope': `business_application:${required('QUALITY_APPLICATION_ID')}`,
}
const fixture = JSON.parse(readFileSync(required('QUALITY_TEST_DATA_FILE'), 'utf8'))
assert(fixture.projectA && fixture.projectB && fixture.projectA !== fixture.projectB)
assert(fixture.inspectorId && fixture.task?.templateId && fixture.task?.lineAssignees?.length)

async function request(method, path, body) {
  const response = await fetch(`${apiBase}${path}`, {
    method, headers, signal: AbortSignal.timeout(60000),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return response.json()
}
async function ok(method, path, body) {
  const result = await request(method, path, body)
  assert.equal(result.status, 200, `${path}: ${result.status}`)
  return result.result
}

const allowed = await ok('GET', `/quality/context/assignment-candidates?projectId=${encodeURIComponent(fixture.projectA)}`)
const denied = await ok('GET', `/quality/context/assignment-candidates?projectId=${encodeURIComponent(fixture.projectB)}`)
assert(allowed.some(user => user.id === fixture.inspectorId))
console.log('PASS authorized project candidate is available')
assert(!denied.some(user => user.id === fixture.inspectorId))
console.log('PASS foreign project candidate is excluded')

const taskName = `assignment-scope-denial-${Date.now()}`
const result = await request('POST', '/quality/tasks', {
  ...fixture.task, id: undefined, version: undefined, taskName, projectId: fixture.projectB,
})
assert([400, 403].includes(result.status))
console.log('PASS forged initial assignment is denied')
const page = await ok('POST', '/quality/tasks/_query', { terms: [{ column: 'taskName', value: taskName }] })
assert.equal(page.total, 0)
console.log('PASS denied assignment leaves no persisted task')
