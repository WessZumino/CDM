﻿# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License. See License.txt in the project root for license information.

import os
import unittest

from cdm.storage import LocalAdapter

from tests.common import async_test, TestHelper


class ImportsTests(unittest.TestCase):
    tests_subpath = os.path.join('Cdm', 'Imports')

    @async_test
    async def test_entity_with_missing_import(self):
        """The path between TestDataPath and TestName."""
        test_name = 'TestEntityWithMissingImport'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        doc = await corpus.fetch_object_async('local:/missingImport.cdm.json')
        self.assertIsNotNone(doc)
        self.assertEqual(1, len(doc.imports))
        self.assertEqual('missing.cdm.json', doc.imports[0].corpus_path)
        self.assertIsNone(doc.imports[0].doc)

    @async_test
    async def test_entity_with_missing_nested_imports_async(self):
        test_name = 'TestEntityWithMissingNestedImportsAsync'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        doc = await corpus.fetch_object_async('local:/missingNestedImport.cdm.json')
        self.assertIsNotNone(doc)
        self.assertEqual(1, len(doc.imports))
        first_import = doc.imports[0].doc
        self.assertEqual(1, len(first_import.imports))
        self.assertEqual('notMissing.cdm.json', first_import.name)
        nested_import = first_import.imports[0].doc
        self.assertIsNone(nested_import)

    @async_test
    async def test_entity_with_same_imports_async(self):
        test_name = 'TestEntityWithSameImportsAsync'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        doc = await corpus.fetch_object_async('local:/multipleImports.cdm.json')
        self.assertIsNotNone(doc)
        self.assertEqual(2, len(doc.imports))
        first_import = doc.imports[0].doc
        self.assertEqual('missingImport.cdm.json', first_import.name)
        self.assertEqual(1, len(first_import.imports))
        second_import = doc.imports[1].doc
        self.assertEqual('notMissing.cdm.json', second_import.name)

    @async_test
    async def test_non_existing_adapter_namespace(self):
        """Test an import with a non-existing namespace name."""
        test_name = 'TestNonExistingAdapterNamespace'
        local_adapter = LocalAdapter(TestHelper.get_input_folder_path(self.tests_subpath, test_name))
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        # Register it as a 'local' adapter.
        corpus.storage.mount('erp', local_adapter)

        # Set local as our default.
        corpus.storage.default_namespace = 'erp'

        # Load a manifest that is trying to import from 'cdm' namespace.
        # The manifest does't exist since the import couldn't get resolved,
        # so the error message will be logged and the null value will be propagated back to a user.
        self.assertIsNone(await corpus.fetch_object_async('erp.missingImportManifest.cdm'))

    @async_test
    async def test_loading_same_imports_async(self):
        """Testing docs that load the same import"""
        test_name = 'TestLoadingSameImportsAsync'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        main_doc = await corpus.fetch_object_async('mainEntity.cdm.json')
        self.assertIsNotNone(main_doc)
        self.assertEqual(2, len(main_doc.imports))

        first_import = main_doc.imports[0].doc
        second_import = main_doc.imports[1].doc

        # since these two imports are loaded asynchronously, we need to make sure that
        # the import that they share (targetImport) was loaded, and that the
        # targetImport doc is attached to both of these import objects
        self.assertEqual(1, len(first_import.imports))
        self.assertIsNotNone(first_import.imports[0].doc)
        self.assertEqual(1, len(second_import.imports))
        self.assertIsNotNone(second_import.imports[0].doc)

    @async_test
    async def test_loading_same_missing_imports_async(self):
        """Testing docs that load the same import"""
        test_name = 'TestLoadingSameMissingImportsAsync'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        main_doc = await corpus.fetch_object_async('mainEntity.cdm.json')
        self.assertIsNotNone(main_doc)
        self.assertEqual(2, len(main_doc.imports))

        # make sure imports loaded correctly, despite them missing imports
        first_import = main_doc.imports[0].doc
        second_import = main_doc.imports[1].doc

        self.assertEqual(1, len(first_import.imports))
        self.assertIsNone(first_import.imports[0].doc)

        self.assertEqual(1, len(second_import.imports))
        self.assertIsNone(first_import.imports[0].doc)

    @async_test
    async def test_loading_already_present_imports_async(self):
        """Testing docs that load the same import"""
        test_name = 'TestLoadingAlreadyPresentImportsAsync'
        corpus = TestHelper.get_local_corpus(self.tests_subpath, test_name)

        # load the first doc
        main_doc = await corpus.fetch_object_async('mainEntity.cdm.json')
        self.assertIsNotNone(main_doc)
        self.assertEqual(1, len(main_doc.imports))

        import_doc = main_doc.imports[0].doc
        self.assertIsNotNone(import_doc)

        # now load the second doc, which uses the same import
        # the import should not be loaded again, it should be the same object
        second_doc = await corpus.fetch_object_async('secondEntity.cdm.json')
        self.assertIsNotNone(second_doc)
        self.assertEqual(1, len(second_doc.imports))

        second_import_doc = main_doc.imports[0].doc
        self.assertIsNotNone(second_import_doc)

        self.assertIs(import_doc, second_import_doc)
