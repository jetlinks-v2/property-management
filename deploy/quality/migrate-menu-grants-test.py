import copy
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('migration', Path(__file__).with_name('migrate-menu-grants.py'))
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class MenuGrantMigrationTest(unittest.TestCase):
    def setUp(self):
        self.definition = {'id': 'quality-system-User', 'parentId': 'quality-system-basic', 'buttons': [
            {'id': 'save', 'permissions': [{'permission': 'property-user', 'actions': ['save']}]},
            {'id': 'sync', 'permissions': [{'permission': 'property-user', 'actions': ['sync']}]}]}
        public = copy.deepcopy(self.definition)
        public.update(id='charging-User', parentId='charging-basic')
        self.definitions = [self.definition, public, {'id': 'charging-basic', 'parentId': '-1', 'buttons': []}]
        self.grant = {'menus': [{'id': 'quality-system-User', 'granted': True,
                                'buttons': [{'id': 'save', 'granted': True}, {'id': 'sync', 'granted': False}]}],
                      'assetAccesses': [{'assetType': 'user', 'accesses': [{'supportId': 'org-include-children'}]}]}

    def test_omitted_definitions_preserve_original_actions_and_ranges(self):
        result = migration.transform(self.grant, [], self.definitions)
        self.assertEqual([b['granted'] for b in result['menus'][0]['buttons']], [True, False])
        self.assertEqual(result['assetAccesses'], self.grant['assetAccesses'])
        self.assertEqual(self.grant['menus'][0]['id'], 'quality-system-User')

    def test_missing_source_definition_fails_instead_of_silently_removing_action(self):
        with self.assertRaisesRegex(ValueError, 'Missing original permission'):
            migration.transform(self.grant, [], self.definitions[1:])

    def test_action_with_new_permission_is_not_added(self):
        self.definitions[1]['buttons'][0]['permissions'][0]['actions'].append('delete')
        result = migration.transform(self.grant, [], self.definitions)
        self.assertFalse(result['menus'][0]['buttons'][0]['granted'])

    def test_idempotence_and_explicit_partial_migration_recovery(self):
        expected = migration.transform(self.grant, [], self.definitions)
        self.assertEqual(migration.transform(expected, [], self.definitions), expected)
        broken = copy.deepcopy(expected)
        broken['menus'][0]['buttons'][0]['granted'] = False
        self.assertEqual(migration.transform(broken, [], self.definitions, self.grant), expected)

    def test_business_actions_preserved_and_parent_granted_from_children(self):
        self.grant['menus'] = [{'id': 'quality-Records-Company', 'granted': True,
                                'buttons': [{'id': 'inspect', 'granted': False}]}]
        business = [{'id': 'quality-CompanyInspection', 'parentId': '-1', 'buttons': []},
                    {'id': 'quality-Records-Company', 'parentId': 'quality-CompanyInspection',
                     'sortIndex': 1, 'icon': 'ProfileOutlined', 'i18nMessages': {}}]
        result = migration.transform(self.grant, business, self.definitions)
        self.assertEqual(result['menus'][0]['buttons'], self.grant['menus'][0]['buttons'])
        self.assertTrue(result['menus'][1]['granted'])
        self.assertEqual(migration.transform(result, business, self.definitions), result)


if __name__ == '__main__':
    unittest.main()
