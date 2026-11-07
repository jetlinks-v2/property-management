import importlib.util
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('migration',Path(__file__).with_name('migrate-project-bindings.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ProjectMigrationTest(unittest.TestCase):
    def snapshot(self):
        return {'tenantId':'tenant','applicationId':'app','projects':[{'id':'a','name':'项目A'},{'id':'b','name':'项目B'}],
            'configurations':[
                {'id':'type','kind':'business-type','name':'旧配置','configLevel':1,'data':{},'version':2},
                {'id':'item','kind':'inspection-content','name':'检查项','configLevel':1,'sourceType':1,'data':{'businessTypeId':'type','checkContentId':'content','score':100}},
                {'id':'template','kind':'template','name':"模板'一",'configLevel':1,'data':{'businessTypeId':'type','configItems':[{'configId':'item'}],'snapshot':[{'configId':'item','score':100,'businessTypeId':'type'}]}}],
            'tasks':[{'id':'task','taskLevel':1,'templateId':'template','projectId':'a'}]}

    def test_mapping_preserves_snapshot_scores_and_retires_old_maintenance(self):
        original=self.snapshot();changes=m.plan(original,{'businessTypes':{'type':'a'}});after={n['id']:n for _,n in changes}
        self.assertEqual(after['template']['projectId'],'a');self.assertEqual(after['template']['data']['snapshot'][0]['score'],100)
        self.assertTrue(after['type']['deleted']);self.assertNotIn('businessTypeId',after['template']['data'])
        self.assertIn('businessTypeId',original['configurations'][2]['data'])
        sql=m.sql(original,changes);self.assertIn("模板''一",sql);self.assertIn('tenant_id=',sql);self.assertIn('version=',sql)
        self.assertIn('IF NOT FOUND',m.sql(original,changes,True))

    def test_missing_mapping_is_not_guessed_from_ids_or_names(self):
        with self.assertRaises(ValueError):m.plan(self.snapshot(),{})

    def test_cross_project_historical_task_requires_split(self):
        snapshot=self.snapshot();snapshot['tasks'][0]['projectId']='b'
        with self.assertRaisesRegex(ValueError,'历史任务'):m.plan(snapshot,{'businessTypes':{'type':'a'}})

    def test_mixed_project_template_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'模板与检查配置'):m.plan(self.snapshot(),{'businessTypes':{'type':'a'},'configurations':{'template':'b'}})

    def test_foreign_tenant_snapshot_is_rejected(self):
        snapshot=self.snapshot();snapshot['configurations'][0]['tenantId']='other'
        with self.assertRaisesRegex(ValueError,'其他租户'):m.plan(snapshot,{'businessTypes':{'type':'a'}})

    def test_configuration_text_cannot_close_sql_block(self):
        snapshot=self.snapshot();snapshot['configurations'][2]['data']['taskDescription']='$quality_project_binding$'
        generated=m.sql(snapshot,m.plan(snapshot,{'businessTypes':{'type':'a'}}))
        self.assertIn('DO $quality_project_binding_$',generated)
        self.assertIn('"taskDescription":"$quality_project_binding$"',generated)

if __name__=='__main__':unittest.main()
