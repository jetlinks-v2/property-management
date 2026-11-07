#!/usr/bin/env python3
"""从授权 API 快照生成 PostgreSQL 项目绑定迁移及回退 SQL；不直接连接或修改数据库。"""
import argparse
import copy
import json
from pathlib import Path


def plan(snapshot, mapping):
    application = snapshot['applicationId']
    tenant = snapshot['tenantId']
    projects = {p['id']: p for p in snapshot['projects']}
    rows = {r['id']: r for r in snapshot['configurations']}
    bindings = {}
    for row in rows.values():
        if row['configLevel'] != 1 or row['kind'] not in ('template', 'inspection-content'):
            continue
        project = row.get('projectId') or mapping.get('configurations', {}).get(row['id']) or mapping.get('businessTypes', {}).get(row.get('data', {}).get('businessTypeId'))
        if project not in projects:
            raise ValueError('配置缺少有效项目映射: ' + row['id'])
        bindings[row['id']] = project
    # 同一模板/检查配置曾跨项目复用时必须先拆分，不能按名称猜测归属。
    for row in rows.values():
        if row['id'] in bindings and row['kind'] == 'template':
            for item in row.get('data', {}).get('configItems', []):
                if bindings.get(item['configId']) != bindings[row['id']]:
                    raise ValueError('模板与检查配置的项目不一致，须先拆分: ' + row['id'])
    for task in snapshot.get('tasks', []):
        if task['taskLevel'] == 1 and bindings.get(task['templateId']) != task['projectId']:
            raise ValueError('历史任务与模板的项目不一致，须先拆分模板: ' + task['id'])
    changes = []
    for old in rows.values():
        if old.get('applicationId', application) != application or old.get('tenantId', tenant) != tenant:
            raise ValueError('快照包含其他租户或应用的配置')
        new = copy.deepcopy(old)
        if old['id'] in bindings:
            project = bindings[old['id']]
            new.update(projectId=project, organizationId=project, projectName=projects[project]['name'])
            data = new['data']
            for key in ('businessTypeId', 'businessTypeName'):
                data.pop(key, None)
            for item in data.get('snapshot', []):
                item.pop('businessTypeId', None); item.pop('businessTypeName', None)
                item['projectId'] = project
            if old['kind'] == 'inspection-content':
                new['uniqueKey'] = 'inspection-content:1:%s:%s:%s' % (project, old.get('sourceType', 1), data['checkContentId'])
            else:
                new['uniqueKey'] = 'template:1:%s:%s:%s' % (project, old.get('parentId') or 'root', old['name'])
        elif old['kind'] == 'business-type':
            new.update(deleted=True, uniqueKey='deleted:' + old['id'])
        if new != old:
            changes.append((old, new))
    # 新唯一键冲突会整批拒绝；不会在发布时静默覆盖另一条检查配置。
    keys = set()
    updated = {new['id']: new for _, new in changes}
    for row in rows.values():
        row = updated.get(row['id'], row)
        if row.get('deleted') or not row.get('uniqueKey'):
            continue
        key = row['uniqueKey']
        if key in keys:
            raise ValueError('迁移后唯一键冲突: ' + key)
        keys.add(key)
    return changes


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    return "'" + str(value).replace("'", "''") + "'"


def sql(snapshot, changes, rollback=False):
    statements = []
    for old, new in changes:
        source, target = (new, old) if rollback else (old, new)
        expected = old.get('version', 0) + (1 if rollback else 0)
        version = old.get('version', 0) + (0 if rollback else 1)
        where = 'id=%s AND application_id=%s AND tenant_id=%s AND version=%s' % (
            literal(old['id']), literal(snapshot['applicationId']), literal(snapshot['tenantId']), expected)
        values = {
            'project_id': target.get('projectId'), 'organization_id': target.get('organizationId'),
            'project_name': target.get('projectName'), 'unique_key': target.get('uniqueKey'),
            'data': json.dumps(target['data'], ensure_ascii=False, separators=(',', ':')),
            'deleted': target.get('deleted', False),
        }
        assignments = ','.join(k+'='+literal(v) for k,v in values.items()) + ',version=' + str(version)
        statements.append('UPDATE pq_configuration SET '+assignments+' WHERE '+where+';')
        statements.append("IF NOT FOUND THEN RAISE EXCEPTION 'configuration changed; abort migration'; END IF;")
    body = 'BEGIN\n' + '\n'.join(statements) + '\nEND'
    # 用户配置可能包含 SQL dollar 引号文本，分隔符必须避开整个块内容。
    delimiter = '$quality_project_binding$'
    while delimiter in body:
        delimiter = delimiter[:-1] + '_$'
    return '\n'.join(['BEGIN;', "SET LOCAL lock_timeout = '5s';",
        'SET LOCAL standard_conforming_strings = on;',
        'LOCK TABLE pq_configuration IN SHARE ROW EXCLUSIVE MODE;',
        'DO ' + delimiter + '\n' + body + '\n' + delimiter + ';', 'COMMIT;', ''])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text())
    changes = plan(snapshot, json.loads(args.mapping.read_text()))
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in [('before.json', snapshot), ('plan.json', [{'id':o['id'],'name':o['name'],'projectId':n.get('projectId'),'deleted':n.get('deleted',False)} for o,n in changes])]:
        p=args.output/name;p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');p.chmod(0o600)
    for name, rollback in [('apply.sql',False),('rollback.sql',True)]:
        p=args.output/name;p.write_text(sql(snapshot,changes,rollback));p.chmod(0o600)
    print('已生成 %d 条配置迁移计划及回退 SQL；数据库未修改。' % len(changes))


if __name__ == '__main__':
    main()
