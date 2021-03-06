// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See License.txt in the project root for license information.

package com.microsoft.commondatamodel.objectmodel.cdm;

import com.google.common.base.Strings;
import com.microsoft.commondatamodel.objectmodel.enums.CdmStatusLevel;
import com.microsoft.commondatamodel.objectmodel.persistence.CdmConstants;
import com.microsoft.commondatamodel.objectmodel.enums.CdmAttributeContextType;
import com.microsoft.commondatamodel.objectmodel.enums.CdmObjectType;
import com.microsoft.commondatamodel.objectmodel.enums.CdmValidationStep;
import com.microsoft.commondatamodel.objectmodel.persistence.PersistenceLayer;
import com.microsoft.commondatamodel.objectmodel.resolvedmodel.ParameterCollection;
import com.microsoft.commondatamodel.objectmodel.resolvedmodel.ResolveContext;
import com.microsoft.commondatamodel.objectmodel.resolvedmodel.ResolvedTrait;
import com.microsoft.commondatamodel.objectmodel.resolvedmodel.ResolvedTraitSet;
import com.microsoft.commondatamodel.objectmodel.storage.StorageAdapter;
import com.microsoft.commondatamodel.objectmodel.storage.StorageManager;
import com.microsoft.commondatamodel.objectmodel.utilities.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.microsoft.commondatamodel.objectmodel.utilities.logger.Logger;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;

public class CdmCorpusDefinition {
  private static AtomicInteger nextId = new AtomicInteger(0);
  private final StorageManager storage;
  private final PersistenceLayer persistence;
  private CdmCorpusContext ctx;
  final private Map<CdmEntityDefinition, ArrayList<CdmE2ERelationship>> outgoingRelationships;
  final private Map<CdmEntityDefinition, ArrayList<CdmE2ERelationship>> incomingRelationships;
  Map<String, String> resEntMap;

  private String appId;
  private String rootPath;
  private Map<String, List<CdmDocumentDefinition>> symbolDefinitions;
  private Map<String, SymbolSet> definitionReferenceSymbols;
  private Map<String, String> definitionWrtTag;
  private Map<String, ResolvedTraitSet> emptyRts;

  private Map<String, CdmObject> objectCache;
  private DocumentLibrary documentLibrary;

  /**
   * Whether we are currently performing a resolution or not.
   * Used to stop making documents dirty during CdmCollections operations.
   */
  boolean isCurrentlyResolving = false;

  /**
   * Used by Visit functions of CdmObjects to skip calculating the declaredPath.
   */
  boolean blockDeclaredPathChanges = false;

  /**
   * The set of resolution directives that will be used by default by the object model when it is resolving
   * entities and when no per-call set of directives is provided.
   */
  private AttributeResolutionDirectiveSet defaultResolutionDirectives;

  public CdmCorpusDefinition() {
    this.symbolDefinitions = new LinkedHashMap<>();
    this.definitionReferenceSymbols = new LinkedHashMap<>();
    this.definitionWrtTag = new LinkedHashMap<>();
    this.emptyRts = new LinkedHashMap<>();

    this.setCtx(new ResolveContext(this));
    this.storage = new StorageManager(this);
    this.persistence = new PersistenceLayer(this);

    this.outgoingRelationships = new LinkedHashMap<>();
    this.incomingRelationships = new LinkedHashMap<>();
    this.resEntMap = new LinkedHashMap<>();
    this.objectCache = new LinkedHashMap<>();
    this.documentLibrary = new DocumentLibrary();

    // the default for the default is to make entity attributes into foreign key references when they point at one other instance and
    // to ignore the other entities when there are an array of them
    Set<String> directives = new LinkedHashSet<> ();
    directives.add("normalized");
    directives.add("referenceOnly");
    this.defaultResolutionDirectives = new AttributeResolutionDirectiveSet(directives);
  }

  static CdmDocumentDefinition fetchPriorityDocument(final List<CdmDocumentDefinition> docs,
                                                            final Map<CdmDocumentDefinition, Integer> importPriority) {
    CdmDocumentDefinition docBest = null;
    int indexBest = Integer.MAX_VALUE;
    for (final CdmDocumentDefinition docDefined : docs) {
      // is this one of the imported docs?
      final boolean worked = importPriority.containsKey(docDefined);
      final int indexFound = importPriority.getOrDefault(docDefined, Integer.MAX_VALUE);

      if (worked && indexFound < indexBest) {
        indexBest = indexFound;
        docBest = docDefined;
        // hard to be better than the best
        if (indexBest == 0) {
          break;
        }
      }
    }
    return docBest;
  }

  static String createCacheKeyFromObject(final CdmObject definition, final String kind) {
    return definition.getId() + "-" + kind;
  }

  private static String pathToSymbol(final String symbol, final CdmDocumentDefinition docFrom, final DocsResult docResultTo) {
    // If no destination is given, then there is no path to look for.
    if (docResultTo.getDocBest() == null) {
      return null;
    }

    // If there, return.
    if (docFrom == docResultTo.getDocBest()) {
      return docResultTo.getNewSymbol();
    }

    // If the to Doc is imported directly here...
    final Integer pri = docFrom.getImportPriorities().getImportPriority()
        .get(docResultTo.getDocBest());
    if (pri != null) {
      // If the imported version is the highest priority, we are good.
      if (docResultTo.getDocList() == null || docResultTo.getDocList().size() == 1) {
        return symbol;
      }

      // More than 1 symbol, see if highest pri.
      Integer maxPri = 0;
      for (final CdmDocumentDefinition docImpl : docResultTo.getDocList()) {
        final Optional<Entry<CdmDocumentDefinition, Integer>> maxEntry = docImpl.getImportPriorities()
            .getImportPriority().entrySet().parallelStream()
            .max(Comparator.comparing(Entry::getValue));

        maxPri = Math.max(maxPri, maxEntry.get().getValue());
      }

      if (maxPri != null && maxPri.equals(pri)) {
        return symbol;
      }
    }

    // Can't get there directly, check the monikers.
    if (null != docFrom.getImportPriorities().getMonikerPriorityMap()) {
      for (final Map.Entry<String, CdmDocumentDefinition> kv : docFrom.getImportPriorities()
          .getMonikerPriorityMap().entrySet()) {
        final String tryMoniker = pathToSymbol(symbol, kv.getValue(), docResultTo);
        if (tryMoniker != null) {
          return String.format("%s/%s", kv.getKey(), tryMoniker);
        }
      }
    }

    return null;
  }

  static CdmObjectType mapReferenceType(final CdmObjectType ofType) {
    switch (ofType) {
      case ArgumentDef:
      case DocumentDef:
      case ManifestDef:
      case Import:
      case ParameterDef:
      default:
        return CdmObjectType.Error;

      case AttributeGroupRef:
      case AttributeGroupDef:
        return CdmObjectType.AttributeGroupRef;

      case ConstantEntityDef:
      case EntityDef:
      case EntityRef:
        return CdmObjectType.EntityRef;

      case DataTypeDef:
      case DataTypeRef:
        return CdmObjectType.DataTypeRef;

      case PurposeDef:
      case PurposeRef:
        return CdmObjectType.PurposeRef;

      case TraitDef:
      case TraitRef:
        return CdmObjectType.TraitRef;

      case EntityAttributeDef:
      case TypeAttributeDef:
      case AttributeRef:
        return CdmObjectType.AttributeRef;

      case AttributeContextDef:
      case AttributeContextRef:
        return CdmObjectType.AttributeContextRef;
    }
  }

  static int getNextId() {
    return nextId.incrementAndGet();
  }

  public boolean validate() {
    return false;
  }

  public String getRootPath() {
    return this.rootPath;
  }

  public void setRootPath(final String value) {
    this.rootPath = value;
  }

  public AttributeResolutionDirectiveSet getDefaultResolutionDirectives() {
    return this.defaultResolutionDirectives;
  }

  public void setDefaultResolutionDirectives(final AttributeResolutionDirectiveSet defaultResolutionDirectives) {
    this.defaultResolutionDirectives = defaultResolutionDirectives;
  }

  public <T extends CdmObject> T makeObject(final CdmObjectType ofType, final String nameOrRef) {
    return this.makeObject(ofType, nameOrRef, false);
  }

  public <T extends CdmObject> T makeObject(final CdmObjectType ofType) {
    return this.makeObject(ofType, null, false);
  }

  private void checkPrimaryKeyAttributes(final CdmEntityDefinition resolvedEntity, final ResolveOptions resOpt) {
    if (resolvedEntity.fetchResolvedTraits(resOpt).find(resOpt, "is.identifiedBy") == null) {
      Logger.warning(
          CdmCorpusDefinition.class.getSimpleName(),
          this.ctx,
          Logger.format("There is a primary key missing for the entry '{0}'.", resolvedEntity.getName())
      );
    }
  }

  String createDefinitionCacheTag(final ResolveOptions resOpt, final CdmObjectBase definition, final String kind) {
    return createDefinitionCacheTag(resOpt, definition, kind, "", false);
  }

  CdmDocumentDefinition addDocumentObjects(final CdmFolderDefinition cdmFolderDefinition, final CdmDocumentDefinition docDef) {
    final CdmDocumentDefinition doc = docDef;
    final String path = this.storage.createAbsoluteCorpusPath(doc.getFolderPath() + doc.getName(), doc)
        .toLowerCase();
    this.documentLibrary.addDocumentPath(path, cdmFolderDefinition, doc);

    return doc;
  }

  String createDefinitionCacheTag(final ResolveOptions resOpt, final CdmObjectBase definition, final String kind,
                                         final String extraTags) {
    return createDefinitionCacheTag(resOpt, definition, kind, extraTags, false);
  }

  String createDefinitionCacheTag(final ResolveOptions resOpt, final CdmObjectBase definition, final String kind,
                                         final String extraTags, final boolean useNameNotId) {
    // construct a tag that is unique for a given object in a given context
    // context is:
    //   (1) the wrtDoc has a set of imports and definitions that may change what the object is point at
    //   (2) there are different kinds of things stored per object (resolved traits, atts, etc.)
    //   (3) the directives from the resolve Options might matter
    //   (4) sometimes the caller needs different caches (extraTags) even give 1-3 are the same
    // the hardest part is (1). To do this, see if the object has a set of reference documents registered.
    // if there is nothing registered, then there is only one possible way to resolve the object so don't include doc info in the tag.
    // if there IS something registered, then the object could be ambiguous. find the 'index' of each of the ref documents (potential definition of something referenced under this scope)
    // in the wrt document's list of imports. sort the ref docs by their index, the relative ordering of found documents makes a unique context.
    // the hope is that many, many different lists of imported files will result in identical reference sortings, so lots of re-use
    // since this is an expensive operation, actually cache the sorted list associated with this object and wrtDoc

    // easy stuff first
    final String thisId;
    final String thisName = definition.fetchObjectDefinitionName();
    if (useNameNotId) {
      thisId = thisName;
    } else {
      thisId = Integer.toString(definition.getId());
    }

    final StringBuilder tagSuffix = new StringBuilder();
    tagSuffix.append(String.format("-%s-%s", kind, thisId));
    tagSuffix.append(String
        .format("-(%s)", resOpt.getDirectives() != null ? resOpt.getDirectives().getTag() : ""));
    if (!Strings.isNullOrEmpty(extraTags)) {
      tagSuffix.append(String.format("-%s", extraTags));
    }

    // is there a registered set? (for the objectdef, not for a reference) of the many symbols involved in defining this thing (might be none)
    final CdmObjectDefinition objDef = definition.fetchObjectDefinition(resOpt);
    SymbolSet symbolsRef = null;
    if (objDef != null) {
      final String key = CdmCorpusDefinition.createCacheKeyFromObject(objDef, kind);
      symbolsRef = this.definitionReferenceSymbols.get(key);
    }

    if (symbolsRef == null && thisName != null) {
      // every symbol should depend on at least itself
      final SymbolSet symSetThis = new SymbolSet();
      symSetThis.add(thisName);
      this.registerDefinitionReferenceSymbols(definition, kind, symSetThis);
      symbolsRef = symSetThis;
    }

    if (symbolsRef != null && symbolsRef.getSize() > 0) {
      // each symbol may have definitions in many documents. use importPriority to figure out which one we want
      final CdmDocumentDefinition wrtDoc = resOpt.getWrtDoc();
      final LinkedHashSet<Integer> foundDocIds = new LinkedHashSet<>();

      if (wrtDoc.getImportPriorities() != null) {
        symbolsRef.forEach(symRef -> {
          // get the set of docs where defined
          final DocsResult docsRes = this
              .docsForSymbol(resOpt, wrtDoc, definition.getInDocument(), symRef);
          // we only add the best doc if there are multiple options
          if (docsRes != null && docsRes.getDocList() != null && docsRes.getDocList().size() > 1) {
            final CdmDocumentDefinition docBest = CdmCorpusDefinition.fetchPriorityDocument(docsRes.getDocList(),
                wrtDoc.getImportPriorities().getImportPriority());
            if (docBest != null) {
              foundDocIds.add(docBest.getId());
            }
          }
        });
      }

      final List<Integer> sortedList = new ArrayList<>(foundDocIds);
      Collections.sort(sortedList);

      final String tagPre = sortedList
          .stream().map(Object::toString)
          .collect(Collectors.joining("-"));

      return tagPre + tagSuffix;
    }
    return null;
  }

  public <T extends CdmObjectReference> T makeRef(final CdmObjectType ofType, final Object refObj,
                                                  final boolean simpleNameRef) {
    CdmObjectReference oRef = null;
    if (refObj != null) {
      if (refObj instanceof CdmObject) {
        if (refObj == ofType) {
          // forgive this mistake, return the ref passed in
          oRef = (CdmObjectReference) refObj;
        } else {
          oRef = makeObject(ofType, null, false);
          oRef.setExplicitReference((CdmObjectDefinition) refObj);
        }
      } else {
        oRef = this.makeObject(ofType, refObj.toString().replaceAll("^\"|\"$", ""), simpleNameRef); // TODO-BQ: Remove the regex replaceAll. Ideally, we should remove Object from the signature completely.
      }
    }
    return (T) oRef;
  }

  public <T extends CdmObject> T makeObject(final CdmObjectType ofType, final String nameOrRef,
                                            final boolean simpleNameRef) {
    CdmObject newObj = null;
    switch (ofType) {
      case ArgumentDef:
        newObj = new CdmArgumentDefinition(this.ctx, nameOrRef);
        break;
      case AttributeContextDef:
        newObj = new CdmAttributeContext(this.ctx, nameOrRef);
        break;
      case AttributeContextRef:
        newObj = new CdmAttributeContextReference(this.ctx, nameOrRef);
        break;
      case AttributeGroupDef:
        newObj = new CdmAttributeGroupDefinition(this.ctx, nameOrRef);
        break;
      case AttributeGroupRef:
        newObj = new CdmAttributeGroupReference(this.ctx, nameOrRef, simpleNameRef);
        break;
      case AttributeRef:
        newObj = new CdmAttributeReference(this.ctx, nameOrRef, simpleNameRef);
        break;
      case AttributeResolutionGuidanceDef:
        newObj = new CdmAttributeResolutionGuidance(this.ctx);
        break;
      case ConstantEntityDef:
        newObj = new CdmConstantEntityDefinition(this.ctx, nameOrRef);
        break;
      case DataPartitionDef:
        newObj = new CdmDataPartitionDefinition(this.ctx, nameOrRef);
        break;
      case DataPartitionPatternDef:
        newObj = new CdmDataPartitionPatternDefinition(this.ctx, nameOrRef);
        break;
      case DataTypeDef:
        newObj = new CdmDataTypeDefinition(this.ctx, nameOrRef, null);
        break;
      case DataTypeRef:
        newObj = new CdmDataTypeReference(this.ctx, nameOrRef, simpleNameRef);
        break;
      case DocumentDef:
        newObj = new CdmDocumentDefinition(this.ctx, nameOrRef);
        break;
      case EntityAttributeDef:
        newObj = new CdmEntityAttributeDefinition(this.ctx, nameOrRef);
        break;
      case EntityDef:
        newObj = new CdmEntityDefinition(this.ctx, nameOrRef, null);
        break;
      case EntityRef:
        newObj = new CdmEntityReference(this.ctx, nameOrRef, simpleNameRef);
        break;
      case FolderDef:
        newObj = new CdmFolderDefinition(this.ctx, nameOrRef);
        break;
      case ManifestDef:
        newObj = new CdmManifestDefinition(this.ctx, nameOrRef);
        break;
      case ManifestDeclarationDef:
        newObj = new CdmManifestDeclarationDefinition(this.ctx, nameOrRef);
        break;
      case Import:
        newObj = new CdmImport(this.ctx, nameOrRef, null);
        break;
      case LocalEntityDeclarationDef:
        newObj = new CdmLocalEntityDeclarationDefinition(this.ctx, nameOrRef);
        break;
      case ParameterDef:
        newObj = new CdmParameterDefinition(this.ctx, nameOrRef);
        break;
      case PurposeDef:
        newObj = new CdmPurposeDefinition(this.ctx, nameOrRef, null);
        break;
      case PurposeRef:
        newObj = new CdmPurposeReference(this.ctx, nameOrRef, simpleNameRef);
        break;
      case ReferencedEntityDeclarationDef:
        newObj = new CdmReferencedEntityDeclarationDefinition(this.ctx, nameOrRef);
        break;
      case TraitDef:
        newObj = new CdmTraitDefinition(this.ctx, nameOrRef, null);
        break;
      case TraitRef:
        newObj = new CdmTraitReference(this.ctx, nameOrRef, simpleNameRef, false);
        break;
      case TypeAttributeDef:
        newObj = new CdmTypeAttributeDefinition(this.ctx, nameOrRef);
        break;
      case E2ERelationshipDef:
        newObj = new CdmE2ERelationship(this.ctx, nameOrRef);
        break;
    }

    return (T) newObj;
  }

  private void registerSymbol(final String symbol, final CdmDocumentDefinition inDoc) {
    final List<CdmDocumentDefinition> docs = this.symbolDefinitions.computeIfAbsent(symbol, k -> new ArrayList<>());
    docs.add(inDoc);
  }

  void removeDocumentObjects(final CdmFolderDefinition cdmFolderDefinition, final CdmDocumentDefinition docDef) {
    final CdmDocumentDefinition doc = docDef;
    // don't worry about definitionWrtTag because it uses the doc ID that won't get re-used in this session unless there are more than 4 billion objects

    // every symbol defined in this document is pointing at the document, so remove from cache.
    // also remove the list of docs that it depends on
    this.removeObjectDefinitions(doc);

    // remove from path lookup, cdmFolderDefinition lookup and global list of documents
    final String path = this.storage.createAbsoluteCorpusPath(doc.getFolderPath() + doc.getName(), doc).toLowerCase();
    this.documentLibrary.removeDocumentPath(path, cdmFolderDefinition, doc);

  }

  private void removeObjectDefinitions(final CdmDocumentDefinition doc) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    doc.visit("", new removeObjectCallBack(this, ctx, doc), null);
  }

  private void unRegisterSymbol(final String symbol, final CdmDocumentDefinition inDoc) {
    final List<CdmDocumentDefinition> docs = this.symbolDefinitions.get(symbol);
    if (docs != null) {
      final int index = docs.indexOf(inDoc);
      if (index != -1) {
        docs.remove(index);
      }
    }
  }

  /**
   * Find import objects for the document that have not been loaded yet.
   */
  void findMissingImportsFromDocument(CdmDocumentDefinition doc) {
    if (doc.getImports() != null) {
      for (int i = 0; i < doc.getImports().size(); i++) {
        final CdmImport anImport = doc.getImports().get(i);
        if (anImport.getDoc() == null) {
          // No document set for this import, see if it is already loaded into the corpus.
          final String path = this.getStorage().createAbsoluteCorpusPath(anImport.getCorpusPath(), doc);
          this.documentLibrary.addToDocsNotLoaded(path);
        }
      }
    }
  }

  void setImportDocuments(CdmDocumentDefinition doc) {
    if (doc.getImports() != null) {
      for (int i = 0; i < doc.getImports().size(); i++) {
        final CdmImport anImport = doc.getImports().get(i);
        if (anImport.getDoc() == null) {
          // no document set for this import, see if it is already loaded into the corpus
          final String path =
                  this.getStorage().createAbsoluteCorpusPath(anImport.getCorpusPath(), doc);

          final CdmDocumentDefinition impDoc = this.documentLibrary.fetchDocumentAndMarkForIndexing(path);

          if (impDoc != null) {
            anImport.setDoc(impDoc);

            // Repeat the process for the import documents.
            this.setImportDocuments(anImport.getDoc());
          }
        }
      }
    }
  }

  CompletableFuture<Void> loadImportsAsync(CdmDocumentDefinition doc, ResolveOptions resOpt) {
    Map<CdmDocumentDefinition, Short> docsNowLoaded = new ConcurrentHashMap<>();
    List<String> docsNotLoaded = this.documentLibrary.listDocsNotLoaded();

    if (docsNotLoaded.size() > 0) {
      Function<String, CompletableFuture<Void>> loadDocs = (missing) ->
              CompletableFuture.runAsync(() -> {
                if (this.documentLibrary.needToLoadDocument(missing)) {
                  // Load it.
                  final CdmDocumentDefinition newDoc =
                          (CdmDocumentDefinition) this.loadFolderOrDocumentAsync(missing, false, resOpt).join();

                  if (this.documentLibrary.markDocumentAsLoadedOrFailed(newDoc, missing, docsNowLoaded)) {
                    Logger.info(
                        CdmCorpusDefinition.class.getSimpleName(),
                        this.ctx,
                        Logger.format("Resolved import for '{0}' {1}.", newDoc.getName(), doc.getAtCorpusPath()),
                        doc.getAtCorpusPath()
                    );
                  } else {
                    Logger.warning(
                        CdmCorpusDefinition.class.getSimpleName(),
                        this.ctx,
                        Logger.format("Unable to resolve import for '{0}' {1}.", missing, doc.getAtCorpusPath()),
                        doc.getAtCorpusPath()
                    );
                  }
                }
              });

      List<CompletableFuture> taskList = new ArrayList<>();
      docsNotLoaded.forEach((key) -> taskList.add(loadDocs.apply(key)));

      // Wait for all of the missing docs to finish loading.
      CompletableFuture.allOf(taskList.toArray(new CompletableFuture[0])).join();

      // Now that we've loaded new docs, find imports from them that need loading.
      docsNowLoaded.forEach((key, value) -> this.findMissingImportsFromDocument(key));

      // Repeat this process for the imports of the imports.
      List<CompletableFuture> importTaskList = new ArrayList<>();
      docsNowLoaded.forEach((key, value) -> importTaskList.add(this.loadImportsAsync(key, resOpt)));

      // Wait for all of the missing docs to finish loading.
      return CompletableFuture.allOf(importTaskList.toArray(new CompletableFuture[0]));
    }
    return CompletableFuture.completedFuture(null);
  }

  CompletableFuture<Void> resolveImportsAsync(final CdmDocumentDefinition doc, ResolveOptions resOpt) {
    // find imports for this doc
    this.findMissingImportsFromDocument(doc);

    // load imports (and imports of imports)
    return this.loadImportsAsync(doc, resOpt).thenRun(() -> {

      // now that everything is loaded, attach import docs to this doc's import list
      this.setImportDocuments(doc);
    });
  }

  private DocsResult docsForSymbol(final ResolveOptions resOpt, final CdmDocumentDefinition wrtDoc, final CdmDocumentDefinition fromDoc, final String symbol) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    final DocsResult result = new DocsResult();
    result.setNewSymbol(symbol);

    // first decision, is the symbol defined anywhere?
    final List<CdmDocumentDefinition> docList = this.symbolDefinitions.get(symbol);
    result.setDocList(docList);
    if (result.getDocList() == null || result.getDocList().size() == 0) {
      // this can happen when the symbol is disambiguated with a moniker for one of the imports used
      // in this situation, the 'wrt' needs to be ignored, the document where the reference is being made has a map of the 'one best' monikered import to search for each moniker

      int preEnd = 0;

      if (symbol != null) {
        preEnd = symbol.indexOf("/");
      }
      if (preEnd == 0) {
        // absolute reference
        Logger.error(
            CdmCorpusDefinition.class.getSimpleName(),
            ctx,
            Logger.format("no support for absolute references yet. fix '{0}'", symbol),
            ctx.getRelativePath()
        );
        return null;
      }
      if (preEnd > 0) {
        final String prefix = StringUtils.slice(symbol, 0, preEnd);
        result.setNewSymbol(StringUtils.slice(symbol, preEnd + 1));
        final List<CdmDocumentDefinition> tempDocList = this.symbolDefinitions.get(result.getNewSymbol());
        result.setDocList(tempDocList);

        CdmDocumentDefinition tempMoniker = null;
        boolean usingWrtDoc = false;

        if (fromDoc != null && fromDoc.getImportPriorities() != null && 
            fromDoc.getImportPriorities().getMonikerPriorityMap() != null &&
            fromDoc.getImportPriorities().getMonikerPriorityMap().containsKey(prefix)) {
          
          tempMoniker = fromDoc.getImportPriorities().getMonikerPriorityMap().get(prefix);

        } else if (wrtDoc != null && wrtDoc.getImportPriorities() != null &&
          wrtDoc.getImportPriorities().getMonikerPriorityMap() != null && 
          wrtDoc.getImportPriorities().getMonikerPriorityMap().containsKey(prefix)) {
          
          // if that didn't work, then see if the wrtDoc can find the moniker.
          tempMoniker = wrtDoc.getImportPriorities().getMonikerPriorityMap().get(prefix);
          usingWrtDoc = true;
        }

        if (tempMoniker != null) {
          // if more monikers, keep looking
          if (result.getNewSymbol().contains("/") && (usingWrtDoc || !this.symbolDefinitions
              .containsKey(result.getNewSymbol()))) {
            final DocsResult currDocsResult =
              docsForSymbol(resOpt, wrtDoc, tempMoniker, result.getNewSymbol());
            if (currDocsResult.getDocList() == null && fromDoc == wrtDoc) {
              // we are back at the top and we have not found the docs, move the wrtDoc down one level
              return this.docsForSymbol(resOpt, tempMoniker, tempMoniker, result.getNewSymbol());
            } else {
              return currDocsResult;
            }
          }
          resOpt.setFromMoniker(prefix);
          result.setDocBest(tempMoniker);
        } else {
          // moniker not recognized in either doc, fail with grace
          result.setNewSymbol(symbol);
          result.setDocList(null);
        }
      }
    }
    return result;
  }

  CdmObjectDefinitionBase resolveSymbolReference(
      final ResolveOptions resOpt,
      final CdmDocumentDefinition fromDoc,
      String symbolDef,
      final CdmObjectType expectedType,
      final boolean retry) {
    final ResolveContext ctx = (ResolveContext) this.ctx;

    // Given a symbolic name, find the 'highest priority' definition of the object from the point
    // of view of a given document (with respect to, wrtDoc) (meaning given a document and the
    // things it defines and the files it imports and the files they import, where is the 'last'
    // definition found).
    if ((resOpt == null || resOpt.getWrtDoc() == null)) {
      // No way to figure this out.
      return null;
    }

    CdmDocumentDefinition wrtDoc = resOpt.getWrtDoc();

    // Get the array of documents where the symbol is defined.
    final DocsResult symbolDocsResult = this.docsForSymbol(resOpt, wrtDoc, fromDoc, symbolDef);

    CdmDocumentDefinition docBest = symbolDocsResult.getDocBest();
    symbolDef = symbolDocsResult.getNewSymbol();
    final List<CdmDocumentDefinition> docs = symbolDocsResult.getDocList();
    if (null != docs) {
      // Add this symbol to the set being collected in resOpt, we will need this when caching.
      if (null == resOpt.getSymbolRefSet()) {
        resOpt.setSymbolRefSet(new SymbolSet());
      }

      resOpt.getSymbolRefSet().add(symbolDef);

      // For the given doc, there is a sorted list of imported docs (including the doc
      // itself as item 0). Find the lowest number imported document that has a definition
      // for this symbol.
      if (null == wrtDoc.getImportPriorities()) {
        return null;
      }

      final Map<CdmDocumentDefinition, Integer> importPriority =
          wrtDoc.getImportPriorities().getImportPriority();

      if (importPriority.size() == 0) {
        return null;
      }

      if (null == docBest) {
        docBest = CdmCorpusDefinition.fetchPriorityDocument(docs, importPriority);
      }
    }

    // Perhaps we have never heard of this symbol in the imports for this document?
    if (null == docBest) {
      return null;
    }

    // Return the definition found in the best document.
    CdmObjectDefinitionBase found = docBest.internalDeclarations.get(symbolDef);
    if (null == found && retry) {
      // Maybe just locatable from here not defined here.
      found = this.resolveSymbolReference(resOpt, docBest, symbolDef, expectedType, false);
    }

    if (null != found && expectedType != CdmObjectType.Error) {
      switch (expectedType) {
        case TraitRef: {
          if (found.getObjectType() != CdmObjectType.TraitDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type trait: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        case DataTypeRef: {
          if (found.getObjectType() != CdmObjectType.DataTypeDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type dataType: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        case EntityRef: {
          if (found.getObjectType() != CdmObjectType.EntityDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type entity: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        case ParameterDef: {
          if (found.getObjectType() != CdmObjectType.ParameterDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type parameter: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        case PurposeRef: {
          if (found.getObjectType() != CdmObjectType.PurposeDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type purpose: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        case AttributeGroupRef: {
          if (found.getObjectType() != CdmObjectType.AttributeGroupDef) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Expected type attributeGroup: '{0}'", symbolDef));
            found = null;
          }

          break;
        }

        default: {
          break;
        }
      }
    }

    return found;
  }

  private void unRegisterDefinitionReferenceSymbols(final CdmObject definition, final String kind) {
    final String key = CdmCorpusDefinition.createCacheKeyFromObject(definition, kind);
    this.definitionReferenceSymbols.remove(key);
  }

  void registerDefinitionReferenceSymbols(final CdmObject definition, final String kind,
                                          final SymbolSet symbolRefSet) {
    final String key = CdmCorpusDefinition.createCacheKeyFromObject(definition, kind);
    final SymbolSet existingSymbols = this.definitionReferenceSymbols.get(key);
    if (existingSymbols == null) {
      // nothing set, just use it
      this.definitionReferenceSymbols.put(key, symbolRefSet);
    } else {
      // something there, need to merge
      existingSymbols.merge(symbolRefSet);
    }
  }

  boolean visit(final String path, final VisitCallback preChildren, final VisitCallback postChildren) {
    return false;
  }

  private CompletableFuture<CdmContainerDefinition> loadFolderOrDocumentAsync(final String objectPath) {
    return loadFolderOrDocumentAsync(objectPath, false);
  }

  boolean indexDocuments(final ResolveOptions resOpt, final CdmDocumentDefinition currentDoc) {
    List<CdmDocumentDefinition> docsNotIndexed = this.documentLibrary.listDocsNotIndexed();

    if (docsNotIndexed.size() > 0) {      // Index any imports.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          Logger.debug(CdmCorpusDefinition.class.getSimpleName(), this.ctx, Logger.format("index start: {0}", doc.getAtCorpusPath()), "indexDocuments");
          doc.clearCaches();
          doc.getImportPriorities();
        }
      }

      // Check basic integrity.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          if (!this.checkObjectIntegrity(doc)) {
            return false;
          }
        }
      }

      // Declare definitions in objects in this doc.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          this.declareObjectDefinitions(doc, "");
        }
      }

      // Make sure we can find everything that is named by reference.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          final ResolveOptions resOptLocal = CdmObjectBase.copyResolveOptions(resOpt);
          resOptLocal.setWrtDoc(doc);
          this.resolveObjectDefinitions(doc, resOptLocal);
        }
      }

      // Now resolve any trait arguments that are type object.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          final ResolveOptions resOptLocal = CdmObjectBase.copyResolveOptions(resOpt);
          resOptLocal.setWrtDoc(doc);
          this.resolveTraitArguments(resOptLocal, doc);
        }
      }

      // Finish up.
      for (final CdmDocumentDefinition doc : docsNotIndexed) {
        if (doc.getNeedsIndexing()) {
          Logger.debug(CdmCorpusDefinition.class.getSimpleName(), this.ctx, Logger.format("index finish: {0}", doc.getAtCorpusPath()), "indexDocuments");
          this.finishDocumentResolve(doc);
        }
      }
    }

    return true;
  }

  private CompletableFuture<CdmContainerDefinition> loadFolderOrDocumentAsync(String objectPath,
                                                                              final boolean forceReload) {
    return loadFolderOrDocumentAsync(objectPath, forceReload, null);
  }

  private CompletableFuture<CdmContainerDefinition> loadFolderOrDocumentAsync(String objectPath,
                                                                              final boolean forceReload,
                                                                              final ResolveOptions resOpt) {
    if (!StringUtils.isNullOrTrimEmpty(objectPath)) {
      // first check for namespace
      final Map.Entry<String, String> pathTuple = this.storage.splitNamespacePath(objectPath);
      final String nameSpace = !StringUtils.isNullOrTrimEmpty(pathTuple.getKey()) ? pathTuple.getKey()
          : this.getStorage().getDefaultNamespace();
      objectPath = pathTuple.getValue();

      if (objectPath.startsWith("/")) {
        final CdmFolderDefinition namespaceFolder = this.storage.fetchRootFolder(nameSpace);
        final StorageAdapter namespaceAdapter = this.storage.fetchAdapter(nameSpace);

        if (namespaceFolder == null || namespaceAdapter == null) {
          Logger.error(
              CdmCorpusDefinition.class.getSimpleName(),
              this.ctx,
              Logger.format("The namespace '{0}' has not been registered, objectPath '{1}'", nameSpace, objectPath),
              "loadFolderOrDocumentAsync"
          );
          return CompletableFuture.completedFuture(null);
        }

        final CdmFolderDefinition lastFolder = namespaceFolder
            .fetchChildFolderFromPathAsync(objectPath, false).join();

        // don't create new folders, just go as far as possible
        if (lastFolder != null) {
          // maybe the search is for a folder?
          final String lastPath = lastFolder.getFolderPath();
          if (lastPath.equals(objectPath)) {
            return CompletableFuture.completedFuture(lastFolder);
          }

          // remove path to folder and then look in the folder
          final String newObjectPath = StringUtils.slice(objectPath, lastPath.length());

          return CompletableFuture.completedFuture(
              lastFolder.fetchDocumentFromFolderPathAsync(newObjectPath, namespaceAdapter, forceReload, resOpt)
                  .join());
        }
      }
    }

    return CompletableFuture.completedFuture(null);
  }

  /**
   * Fetches an object by the path from the corpus.
   *
   * @param <T>        Type of the object to be fetched.
   * @param objectPath Object path, absolute or relative.
   * @return The object obtained from the provided path.
   * @see #fetchObjectAsync(String, CdmObject)
   */
  public <T extends CdmObject> CompletableFuture<T> fetchObjectAsync(final String objectPath) {
    return fetchObjectAsync(objectPath, null).thenApply(cdmObject -> cdmObject != null ? (T) cdmObject : null);
  }

  /**
   * Fetches an object by the path from the corpus, with the CDM object specified.
   *
   * @param <T>        Type of the object to be fetched.
   * @param objectPath Object path, absolute or relative.
   * @param cdmObject  Optional parameter. When provided, it is used to obtain the FolderPath and
   *                   the Namespace needed to create the absolute path from a relative path.
   * @return The object obtained from the provided path.
   */
  public <T extends CdmObject> CompletableFuture<T> fetchObjectAsync(
      final String objectPath,
      final CdmObject cdmObject) {
    return
        fetchObjectAsync(objectPath, cdmObject, false, false)
            .thenApply(fetchedCdmObject -> fetchedCdmObject != null ? (T) fetchedCdmObject : null);
  }

  /**
   * Fetches an object by the path from the corpus, with the CDM object specified.
   *
   * @param <T>        Type of the object to be fetched.
   * @param objectPath Object path, absolute or relative.
   * @param cdmObject  Optional parameter. When provided, it is used to obtain the FolderPath and
   *                   the Namespace needed to create the absolute path from a relative path.
   * @param shallowValidation Optional parameter. When provided, shallow validation in ResolveOptions is enabled,
   *                          which logs errors regarding resolving/loading references as warnings.
   * @return The object obtained from the provided path.
   */
  public <T extends CdmObject> CompletableFuture<T> fetchObjectAsync(
      final String objectPath,
      final CdmObject cdmObject,
      final boolean shallowValidation) {
    return
        fetchObjectAsync(objectPath, cdmObject, false, shallowValidation)
            .thenApply(fetchedCdmObject -> fetchedCdmObject != null ? (T) fetchedCdmObject : null);
  }

  CompletableFuture<CdmObject> fetchObjectAsync(
      final String objectPath,
      final CdmObject cdmObject,
      final boolean forceReload,
      final boolean shallowValidation) {
    // isRootManifestPath is required to deal with the load of the initial root manifest.
    // In this case the the file name can be something different than a CDM CdmManifestDefinition,
    // e.g.: "model.json".

    final String absolutePath = this.storage.createAbsoluteCorpusPath(objectPath, cdmObject);

    String documentPath = absolutePath;
    int documentNameIndex = absolutePath.lastIndexOf(CdmConstants.CDM_EXTENSION);

    if (documentNameIndex != -1) {
      // entity path has to have at least one slash with the entity name at the end
      documentNameIndex += CdmConstants.CDM_EXTENSION.length();
      documentPath = absolutePath.substring(0, documentNameIndex);
    }

    Logger.debug(CdmCorpusDefinition.class.getSimpleName(), this.ctx, Logger.format("request object: {0}", objectPath), "fetchObjectAsync");

    final String finalDocumentPath = documentPath;
    final int finalDocumentNameIndex = documentNameIndex;
    return this.loadFolderOrDocumentAsync(finalDocumentPath, forceReload).thenCompose(loadedCdmObject -> {
      if (loadedCdmObject != null) {
        // get imports and index each document that is loaded
        if (loadedCdmObject instanceof CdmDocumentDefinition) {
          final ResolveOptions resOpt = new ResolveOptions();
          resOpt.setWrtDoc((CdmDocumentDefinition) loadedCdmObject);
          resOpt.setDirectives(new AttributeResolutionDirectiveSet());
          resOpt.setShallowValidation(shallowValidation);

          if (!((CdmDocumentDefinition) loadedCdmObject).indexIfNeededAsync(resOpt).join()) {
            return null;
          }
        }

        if (Objects.equals(finalDocumentPath, absolutePath)) {
          return CompletableFuture.completedFuture(loadedCdmObject);
        }

        if (finalDocumentNameIndex == -1) {
          return CompletableFuture.completedFuture(null);
        }

        // trim off the document path to get the object path in the doc
        final String remainingObjectPath = absolutePath.substring(finalDocumentNameIndex + 1);

        final CdmObject result = ((CdmDocumentDefinition) loadedCdmObject).fetchObjectFromDocumentPath(remainingObjectPath);
        if (null == result) {
          Logger.error(
              CdmCorpusDefinition.class.getSimpleName(),
              this.ctx,
              Logger.format("Could not find symbol '{0}' in document[{1}]", remainingObjectPath, loadedCdmObject.getAtCorpusPath()),
              "fetchObjectAsync"
          );
        }

        return CompletableFuture.completedFuture(result);
      }

      return CompletableFuture.completedFuture(null);
    });
  }

  public void setEventCallback(EventCallback status) {
    setEventCallback(status, CdmStatusLevel.Info);
  }

  public void setEventCallback(EventCallback status, CdmStatusLevel reportAtLevel) {
    ResolveContext ctx = (ResolveContext) this.ctx;
    ctx.setStatusEvent(status);
    ctx.setReportAtLevel(reportAtLevel);
  }

  private CompletableFuture<Void> visitManifestTreeAsync(
      final CdmManifestDefinition manifest,
      final List<CdmEntityDefinition> entitiesInManifestTree) {
    return CompletableFuture.runAsync(() -> {
      final CdmEntityCollection entities = manifest.getEntities();
      if (entities != null) {
        for (final CdmEntityDeclarationDefinition entity : entities) {
          CdmObject currentFile = manifest;
          CdmEntityDeclarationDefinition currentEnt = entity;
          while ((currentEnt instanceof CdmReferencedEntityDeclarationDefinition)) {
            currentEnt = this.<CdmReferencedEntityDeclarationDefinition>fetchObjectAsync(
                currentEnt.getEntityPath(), currentFile).join();
            currentFile = currentEnt;
          }

          final CdmEntityDefinition entityDef = this.<CdmEntityDefinition>fetchObjectAsync(
              currentEnt.getEntityPath(), currentFile).join();
          entitiesInManifestTree.add(entityDef);
        }
      }

      final CdmCollection<CdmManifestDeclarationDefinition> subManifests = manifest.getSubManifests();
      if (subManifests == null) {
        return;
      }

      subManifests.forEach(subFolder -> {
        final CdmManifestDefinition childManifest =
            this.<CdmManifestDefinition>fetchObjectAsync(subFolder.getDefinition(), manifest).join();
        this.visitManifestTreeAsync(childManifest, entitiesInManifestTree).join();
      });
    });
  }


  private CompletableFuture<Void> generateWarningsForSingleDoc(
      final Pair<CdmFolderDefinition, CdmDocumentDefinition> fd,
      final ResolveOptions resOpt) {
    final CdmDocumentDefinition doc = fd.getRight();

    if (doc.getDefinitions() == null) {
      return CompletableFuture.completedFuture(null);
    }

    resOpt.setWrtDoc(doc);

    return CompletableFuture.runAsync(() ->
        doc.getDefinitions().getAllItems()
            .parallelStream()
            .map(element -> {
              if (element instanceof CdmEntityDefinition) {
                final CdmEntityDefinition entity = ((CdmEntityDefinition) element);
                if (entity.getAttributes().getCount() > 0) {
                  final CdmEntityDefinition resolvedEntity = entity.createResolvedEntityAsync(
                      entity.getName() + "_", resOpt)
                      .join();

                  // TODO: Add additional checks here.
                  this.checkPrimaryKeyAttributes(resolvedEntity, resOpt);
                }
                return entity;
              }
              return null;
            }));
  }

  /**
   * Returns a list of relationships where the input entity is the incoming entity.
   *
   * @param entity The input entity.
   */
  public ArrayList<CdmE2ERelationship> fetchIncomingRelationships(final CdmEntityDefinition entity) {
    if (this.incomingRelationships != null && this.incomingRelationships.containsKey(entity)) {
      return this.incomingRelationships.get(entity);
    }
    return new ArrayList<>();
  }

  /**
   * Returns a list of relationships where the input entity is the outgoing entity.
   *
   * @param entity The input entity.
   */
  public ArrayList<CdmE2ERelationship> fetchOutgoingRelationships(final CdmEntityDefinition entity) {
    if (this.outgoingRelationships != null && this.outgoingRelationships.containsKey(entity)) {
      return this.outgoingRelationships.get(entity);
    }
    return new ArrayList<>();
  }

  /**
   * Calculates the entity to entity relationships for all the entities present in the manifest and
   * its sub-manifests.
   *
   * @param currManifest The manifest (and any sub-manifests it contains) that we want to calculate
   *                     relationships for.
   * @return A {@link CompletableFuture<Void>} for the completion of entity graph calculation.
   */
  public CompletableFuture<Void> calculateEntityGraphAsync(final CdmManifestDefinition currManifest) {
    return CompletableFuture.runAsync(() -> {
      if (currManifest.getEntities() != null) {
        for (final CdmEntityDeclarationDefinition entityDec : currManifest.getEntities()) {
          final String entityPath =
              currManifest.createEntityPathFromDeclarationAsync(entityDec, currManifest).join();
          // The path returned by GetEntityPathFromDeclaration is an absolute path.
          // No need to pass the manifest to FetchObjectAsync.
          final CdmEntityDefinition entity =
              this.<CdmEntityDefinition>fetchObjectAsync(entityPath).join();

          if (entity == null) {
            continue;
          }
          final CdmEntityDefinition resEntity;
          // make options wrt this entity document and "relational" always
          Set<String> directives = new LinkedHashSet<> ();
          directives.add("normalized");
          directives.add("referenceOnly");
          final ResolveOptions resOpt = new ResolveOptions(entity.getInDocument(), new AttributeResolutionDirectiveSet(directives));
          final boolean isResolvedEntity = entity.getAttributeContext() != null;

          // only create a resolved entity if the entity passed in was not a resolved entity
          if (!isResolvedEntity) {
            // first get the resolved entity so that all of the references are present
            resEntity = entity.createResolvedEntityAsync("wrtSelf_" + entity.getEntityName(), resOpt).join();
          } else {
            resEntity = entity;
          }

          // find outgoing entity relationships using attribute context
          final ArrayList<CdmE2ERelationship> outgoingRelationships =
              this.findOutgoingRelationships(resOpt, resEntity, resEntity.getAttributeContext(), isResolvedEntity);

          this.outgoingRelationships.put(entity, outgoingRelationships);

          // flip outgoing entity relationships list to get incoming relationships map
          if (outgoingRelationships != null) {
            for (final CdmE2ERelationship outgoingRelationship : outgoingRelationships) {
              final CdmEntityDefinition targetEnt =
                  this.<CdmEntityDefinition>fetchObjectAsync(
                      outgoingRelationship.getToEntity(),
                      currManifest
                  ).join();
              if (targetEnt != null) {
                if (!this.incomingRelationships.containsKey(targetEnt)) {
                  this.incomingRelationships.put(
                      targetEnt,
                      new ArrayList<>()
                  );
                }

                this.incomingRelationships.get(targetEnt).add(outgoingRelationship);
              }
            }
          }

          // delete the resolved entity if we created one here
          if (!isResolvedEntity) {
            resEntity.getInDocument()
                .getFolder()
                .getDocuments()
                .remove(resEntity.getInDocument().getName());
          }
        }
      }

      if (currManifest.getSubManifests() != null) {
        for (final CdmManifestDeclarationDefinition subManifestDef : currManifest.getSubManifests()) {
          final CdmManifestDefinition subManifest =
              this.<CdmManifestDefinition>fetchObjectAsync(
                  subManifestDef.getDefinition(),
                  currManifest)
                  .join();
          if (subManifest != null) {
            this.calculateEntityGraphAsync(subManifest).join();
          }
        }
      }
    });
  }

  private ArrayList<CdmE2ERelationship> findOutgoingRelationships(
      final ResolveOptions resOpt,
      final CdmEntityDefinition resEntity,
      final CdmAttributeContext attCtx) {
    return findOutgoingRelationships(resOpt, resEntity, attCtx, false, null);
  }

  private ArrayList<CdmE2ERelationship> findOutgoingRelationships(
      final ResolveOptions resOpt,
      final CdmEntityDefinition resEntity,
      final CdmAttributeContext attCtx,
      final boolean isResolvedEntity) {
    return findOutgoingRelationships(resOpt, resEntity, attCtx, isResolvedEntity, null);
  }

  private ArrayList<CdmE2ERelationship> findOutgoingRelationships(
      final ResolveOptions resOpt,
      final CdmEntityDefinition resEntity,
      final CdmAttributeContext attCtx,
      final boolean isResolvedEntity,
      CdmAttributeContext generatedAttSetContext) {
    final ArrayList<CdmE2ERelationship> outRels = new ArrayList<>();

    if (attCtx != null && attCtx.getContents() != null) {
      // as we traverse the context tree, look for these nodes which hold the foreign key
      // once we find a context node that refers to an entity reference, we will use the
      // nearest _generatedAttributeSet (which is above or at the same level as the entRef context)
      // and use its foreign key
      CdmAttributeContext newGenSet = (CdmAttributeContext)attCtx.getContents().item("_generatedAttributeSet");
      if (newGenSet == null) {
        newGenSet = generatedAttSetContext;
      }

      for (final Object subAttCtx : attCtx.getContents()) {
        // find entity references that identifies the 'this' entity
        final CdmAttributeContext child = subAttCtx instanceof CdmAttributeContext
            ? (CdmAttributeContext) subAttCtx
            : null;
        if (child != null
            && child.getDefinition() != null
            && child.getDefinition().getObjectType() == CdmObjectType.EntityRef) {
          final List<String> toAtt = (child.getExhibitsTraits().getAllItems())
              .parallelStream()
              .filter(x -> "is.identifiedBy".equals(x.fetchObjectDefinitionName())
                  && x.getArguments().getCount() > 0)
              .map(y -> {
                String namedRef =
                    ((CdmAttributeReference) y
                        .getArguments()
                        .getAllItems()
                        .get(0)
                        .getValue())
                        .getNamedReference();
                return namedRef.substring(namedRef.lastIndexOf("/") + 1);
              }).collect(Collectors.toList());

          final CdmEntityDefinition toEntity = child.getDefinition().fetchObjectDefinition(resOpt);

          // entity references should have the "is.identifiedBy" trait, and the entity ref should be valid
          if (toAtt.size() == 1 && toEntity != null) {
            // get the attribute name from the foreign key
            final String foreignKey = findAddedAttributeIdentity(newGenSet);

            if (!foreignKey.isEmpty()) {
              final String fromAtt = foreignKey
                  .substring(foreignKey.lastIndexOf("/") + 1)
                  .replace(child.getName() + "_", "");

              final CdmE2ERelationship newE2ERel = new CdmE2ERelationship(this.ctx, "");

              if (isResolvedEntity) {
                newE2ERel.setFromEntity(resEntity.getAtCorpusPath());
                if (this.resEntMap.containsKey(toEntity.getAtCorpusPath())) {
                  newE2ERel.setToEntity(this.resEntMap.get(toEntity.getAtCorpusPath()));
                } else {
                  newE2ERel.setToEntity(toEntity.getAtCorpusPath());
                }
              } else {
                // find the path of the unresolved entity using the attribute context of the resolved entity
                CdmObjectReference refToLogicalEntity = resEntity.getAttributeContext().getDefinition();

                CdmEntityDefinition unResolvedEntity = null;
                if (refToLogicalEntity != null) {
                  unResolvedEntity = refToLogicalEntity.<CdmEntityDefinition>fetchObjectDefinition(resOpt);
                }
                final CdmEntityDefinition selectedEntity = unResolvedEntity != null ? unResolvedEntity : resEntity;
                final String selectedEntCorpusPath = unResolvedEntity != null ? unResolvedEntity.getAtCorpusPath() : resEntity.getAtCorpusPath().replace("wrtSelf_", "");

                newE2ERel.setFromEntity(this.getStorage().createAbsoluteCorpusPath(selectedEntCorpusPath, selectedEntity));
                newE2ERel.setToEntity(toEntity.getAtCorpusPath());
              }
              newE2ERel.setFromEntityAttribute(fromAtt);
              newE2ERel.setToEntityAttribute(toAtt.get(0));

              outRels.add(newE2ERel);
            }
          }
        }

        // repeat the process on the child node
        final ArrayList<CdmE2ERelationship>
            subOutRels = this.findOutgoingRelationships(resOpt, resEntity, child, isResolvedEntity, newGenSet);
        outRels.addAll(subOutRels);
      }
    }
    return outRels;
  }

  private String findAddedAttributeIdentity(final CdmAttributeContext context) {
    if (context != null && context.getContents() != null) {
      for (final Object sub : context.getContents()) {
        if (sub instanceof CdmAttributeContext) {
          final CdmAttributeContext subCtx = (CdmAttributeContext) sub;
          if (subCtx.getType() == CdmAttributeContextType.Entity) {
            continue;
          }
          final String fk = findAddedAttributeIdentity(subCtx);
          if (!fk.isEmpty()) {
            return fk;
          } else if (subCtx.getType() == CdmAttributeContextType.AddedAttributeIdentity && subCtx.getContents().size() > 0) {
            // the foreign key is found in the first of the array of the "AddedAttributeIdentity" context type
            return ((CdmObjectReference)subCtx.getContents().get(0)).getNamedReference();
          }
        }
      }
    }
    return "";
  }

  /**
   * Resolves references according to the provided stages and validates.
   *
   * @return The validation step that follows the completed step.
   */
  public CompletableFuture<CdmValidationStep> resolveReferencesAndValidateAsync(
      final CdmValidationStep stage,
      final CdmValidationStep stageThrough) {
    return resolveReferencesAndValidateAsync(
        stage,
        stageThrough,
        null);
  }

  /**
   * Resolves references according to the provided stages and validates.
   *
   * @return The validation step that follows the completed step.
   */
  private CompletableFuture<CdmValidationStep> resolveReferencesAndValidateAsync(
      final CdmValidationStep stage,
      final CdmValidationStep stageThrough,
      final ResolveOptions resOpt) {
    return CompletableFuture.supplyAsync(() -> {
      // Use the provided directives or use the current default.
      final AttributeResolutionDirectiveSet directives;
      if (null != resOpt) {
        directives = resOpt.getDirectives();
      } else {
        directives = this.defaultResolutionDirectives;
      }

      final ResolveOptions finalResolveOptions = new ResolveOptions();
      finalResolveOptions.setWrtDoc(null);
      finalResolveOptions.setDirectives(directives);
      finalResolveOptions.setRelationshipDepth(0);

      for (final CdmDocumentDefinition doc : this.documentLibrary.listAllDocuments()) {
        doc.indexIfNeededAsync(resOpt).join();
      }

      final boolean finishResolve = stageThrough == stage;
      switch (stage) {
        case Start:
        case TraitAppliers: {
          return this.resolveReferencesStep(
              "Defining traits...",
              (CdmDocumentDefinition currentDoc, ResolveOptions resOptions, MutableInt entityNesting) -> {},
              finalResolveOptions,
              true,
              finishResolve || stageThrough == CdmValidationStep.MinimumForResolving,
              CdmValidationStep.Traits);
        }

        case Traits: {
          this.resolveReferencesStep(
              "Resolving traits...",
              this::resolveTraits,
              finalResolveOptions,
              false,
              finishResolve,
              CdmValidationStep.Traits);

          return this.resolveReferencesStep(
              "Checking required arguments...",
              this::resolveReferencesTraitsArguments,
              finalResolveOptions,
              true,
              finishResolve,
              CdmValidationStep.Attributes);
        }

        case Attributes: {
          return this.resolveReferencesStep(
              "Resolving attributes...",
              this::resolveAttributes,
              finalResolveOptions,
              true,
              finishResolve,
              CdmValidationStep.EntityReferences);
        }

        case EntityReferences:
          return this.resolveReferencesStep(
              "Resolving foreign key references...",
              this::resolveForeignKeyReferences,
              finalResolveOptions,
              true,
              true,
              CdmValidationStep.Finished);

        default: {
          break;
        }
      }

      // I'm the bad step.
      return CdmValidationStep.Error;
    });
  }

  Object constTypeCheck(
      final ResolveOptions resOpt,
      final CdmDocumentDefinition currentDoc,
      final CdmParameterDefinition paramDef,
      final Object aValue) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    Object replacement = aValue;

    // If parameter type is entity, then the value should be an entity or ref to one
    // same is true of 'dataType' data type.
    if (null != paramDef.getDataTypeRef()) {
      CdmDataTypeDefinition dt = paramDef.getDataTypeRef().fetchObjectDefinition(resOpt);
      if (null == dt) {
        dt = paramDef.getDataTypeRef().fetchObjectDefinition(resOpt);
        Logger.error(
          CdmCorpusDefinition.class.getSimpleName(),
          ctx,
          Logger.format("parameter '{0}' has an unexpected dataType.", paramDef.getName()),
          ctx.getRelativePath()
        );
        return null;
      }

      // Compare with passed in value or default for parameter.
      Object pValue = aValue;
      if (null == pValue) {
        pValue = paramDef.getDefaultValue();
        replacement = pValue;
      }

      if (null != pValue) {
        if (dt.isDerivedFrom("cdmObject", resOpt)) {
          final List<CdmObjectType> expectedTypes = new ArrayList<>();
          String expected = null;
          if (dt.isDerivedFrom("entity", resOpt)) {
            expectedTypes.add(CdmObjectType.ConstantEntityDef);
            expectedTypes.add(CdmObjectType.EntityRef);
            expectedTypes.add(CdmObjectType.EntityDef);
            expected = "entity";
          } else if (dt.isDerivedFrom("attribute", resOpt)) {
            expectedTypes.add(CdmObjectType.AttributeRef);
            expectedTypes.add(CdmObjectType.TypeAttributeDef);
            expectedTypes.add(CdmObjectType.EntityAttributeDef);
            expected = "attribute";
          } else if (dt.isDerivedFrom("dataType", resOpt)) {
            expectedTypes.add(CdmObjectType.DataTypeRef);
            expectedTypes.add(CdmObjectType.DataTypeDef);
            expected = "dataType";
          } else if (dt.isDerivedFrom("purpose", resOpt)) {
            expectedTypes.add(CdmObjectType.PurposeRef);
            expectedTypes.add(CdmObjectType.PurposeDef);
            expected = "purpose";
          } else if (dt.isDerivedFrom("trait", resOpt)) {
            expectedTypes.add(CdmObjectType.TraitRef);
            expectedTypes.add(CdmObjectType.TraitDef);
            expected = "trait";
          } else if (dt.isDerivedFrom("attributeGroup", resOpt)) {
            expectedTypes.add(CdmObjectType.AttributeGroupRef);
            expectedTypes.add(CdmObjectType.AttributeGroupDef);
            expected = "attributeGroup";
          }

          if (expectedTypes.size() == 0) {
            Logger.error(
                CdmCorpusDefinition.class.getSimpleName(),
                ctx,
                Logger.format("CdmParameterDefinition '{0}' has an unexpected data type.", paramDef.getName()),
                ctx.getRelativePath()
            );
          }

          // If a string constant, resolve to an object ref.
          CdmObjectType foundType = CdmObjectType.Error;
          final Class pValueType = pValue.getClass();

          if (CdmObject.class.isAssignableFrom(pValueType)) {
            foundType = ((CdmObject) pValue).getObjectType();
          }

          String foundDesc = ctx.getRelativePath();

          String pValueAsString = "";
          if (!(pValue instanceof CdmObject)) {
            pValueAsString = (String) pValue;
          }

          if (!pValueAsString.isEmpty()) {
            if (pValueAsString.equalsIgnoreCase("this.attribute")
                && expected.equalsIgnoreCase("attribute")) {
              // Will get sorted out later when resolving traits.
              foundType = CdmObjectType.AttributeRef;
            } else {
              foundDesc = pValueAsString;
              final int seekResAtt = CdmObjectReferenceBase.offsetAttributePromise(pValueAsString);
              if (seekResAtt >= 0) {
                // Get an object there that will get resolved later after resolved attributes.
                replacement = new CdmAttributeReference(ctx, pValueAsString, true);
                ((CdmAttributeReference) replacement).setCtx(ctx);
                ((CdmAttributeReference) replacement).setInDocument(currentDoc);
                foundType = CdmObjectType.AttributeRef;
              } else {
                final CdmObjectDefinitionBase lu = ctx.getCorpus()
                    .resolveSymbolReference(
                        resOpt,
                        currentDoc,
                        pValueAsString,
                        CdmObjectType.Error,
                        true);
                if (null != lu) {
                  if (expected.equalsIgnoreCase("attribute")) {
                    replacement = new CdmAttributeReference(ctx, pValueAsString, true);
                    ((CdmAttributeReference) replacement).setCtx(ctx);
                    ((CdmAttributeReference) replacement).setInDocument(currentDoc);
                    foundType = CdmObjectType.AttributeRef;
                  } else {
                    replacement = lu;
                    foundType = ((CdmObject) replacement).getObjectType();
                  }
                }
              }
            }
          }

          if (expectedTypes.indexOf(foundType) == -1) {
            Logger.error(
                CdmCorpusDefinition.class.getSimpleName(),
                ctx,
                Logger.format("CdmParameterDefinition '{0}' has the dataType of '{1}' but the value '{2}' doesn't resolve to a known '{3}' reference", paramDef.getName(), expected, foundDesc, expected),
                currentDoc.getFolderPath() + ctx.getRelativePath()
            );
          } else {
            Logger.info(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("Resolved '{0}'", foundDesc), ctx.getRelativePath());
          }
        }
      }
    }

    return replacement;
  }

  private CdmValidationStep resolveReferencesStep(
      final String statusMessage,
      final ResolveAction resolveAction,
      final ResolveOptions resolveOpt,
      final boolean stageFinished,
      final boolean finishResolve,
      final CdmValidationStep nextStage) {
    final ResolveContext ctx = (ResolveContext) this.ctx;

    Logger.debug(CdmCorpusDefinition.class.getSimpleName(), ctx, statusMessage);

    final MutableInt entityNesting = new MutableInt(0);
    for (final CdmDocumentDefinition doc : this.documentLibrary.listAllDocuments()) {
      // Cache import documents.
      CdmDocumentDefinition currentDoc = doc;
      resolveOpt.setWrtDoc(currentDoc);
      resolveAction.invoke(currentDoc, resolveOpt, entityNesting);
    }

    if (stageFinished) {
      if (finishResolve) {
        this.finishResolve();
        return CdmValidationStep.Finished;
      }

      return nextStage;
    }

    return nextStage;
  }

  private boolean checkObjectIntegrity(final CdmDocumentDefinition currentDoc) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    final AtomicInteger errorCount = new AtomicInteger();
    final VisitCallback preChildren = (iObject, path) -> {
      if (!iObject.validate()) {
        errorCount.getAndIncrement();
      } else {
        iObject.setCtx(ctx);
      }

      Logger.info(
          CdmCorpusDefinition.class.getSimpleName(),
          ctx,
          Logger.format("Checked, folderPath: '{0}', path: '{1}'", currentDoc.getFolderPath(), path),
          currentDoc.getFolderPath() + path
      );
      return false;
    };

    currentDoc.visit("", preChildren, null);
    return Objects.equals(errorCount.get(), 0);
  }

  private void declareObjectDefinitions(
      final CdmDocumentDefinition currentDoc,
      final String relativePath) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    final String corpusPathRoot = currentDoc.getFolderPath() + currentDoc.getName();
    currentDoc.visit(relativePath, (iObject, path) -> {
      if (path.indexOf("(unspecified)") > 0) {
        return true;
      }

      switch (iObject.getObjectType()) {
        case EntityDef:
        case ParameterDef:
        case TraitDef:
        case PurposeDef:
        case AttributeContextDef:
        case DataTypeDef:
        case TypeAttributeDef:
        case EntityAttributeDef:
        case AttributeGroupDef:
        case ConstantEntityDef:
        case LocalEntityDeclarationDef:
        case ReferencedEntityDeclarationDef: {
          ctx.setRelativePath(relativePath);
          final String corpusPath;
          if (corpusPathRoot.endsWith("/") || path.startsWith("/")) {
            corpusPath = corpusPathRoot + path;
          } else {
            corpusPath = corpusPathRoot + "/" + path;
          }
          if (currentDoc.internalDeclarations.containsKey(path)) {
            Logger.error(
                CdmCorpusDefinition.class.getSimpleName(),
                ctx,
                Logger.format("Duplicate declaration for item: '{0}'", corpusPath)
            );

            return false;
          }

          currentDoc.internalDeclarations.putIfAbsent(path, (CdmObjectDefinitionBase) iObject);

          this.registerSymbol(path, currentDoc);
          Logger.info(
              CdmCorpusDefinition.class.getSimpleName(),
              ctx,
              Logger.format("Declared: '{0}'", corpusPath)
          );
          break;
        }

        default: {
          Logger.debug(
              CdmCorpusDefinition.class.getSimpleName(),
              ctx,
              Logger.format("ObjectType not recognized: '{0}'", iObject.getObjectType().name())
          );
          break;
        }
      }

      return false;
    }, null);
  }

  private void resolveObjectDefinitions(
      final CdmDocumentDefinition currentDoc,
      final ResolveOptions resOpt) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    resOpt.setIndexingDoc(currentDoc);

    currentDoc.visit("", (iObject, path) -> {
      final CdmObjectType objectType = iObject.getObjectType();
      switch (objectType) {
        case AttributeRef:
        case AttributeGroupRef:
        case AttributeContextRef:
        case DataTypeRef:
        case EntityRef:
        case PurposeRef:
        case TraitRef: {
          ctx.setRelativePath(path);
          final CdmObjectReferenceBase objectRef = (CdmObjectReferenceBase) iObject;

          if (CdmObjectReferenceBase.offsetAttributePromise(objectRef.getNamedReference()) < 0) {
            final CdmObjectDefinition resNew = objectRef.fetchObjectDefinition(resOpt);

            if (null == resNew) {
              String message = Logger.format(
                  "Unable to resolve the reference: '{0}' to a known object, folderPath: '{1}', path: '{2}'",
                  objectRef.getNamedReference(),
                  currentDoc.getFolderPath(),
                  path
              );
              String messagePath = currentDoc.getFolderPath() + path;
              // It's okay if references can't be resolved when shallow validation is enabled.
              if (resOpt.getShallowValidation()) {
                Logger.warning(CdmCorpusDefinition.class.getSimpleName(), ctx, message, messagePath);
              } else {
                Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, message, messagePath);
              }

              final CdmObjectDefinition debugRes = objectRef.fetchObjectDefinition(resOpt);
            } else {
              Logger.info(
                  CdmCorpusDefinition.class.getSimpleName(),
                  ctx,
                  Logger.format("Resolved folderPath: '{0}', path: '{1}'", currentDoc.getFolderPath(), path),
                  currentDoc.getFolderPath() + path
              );
            }
          }

          break;
        }

        default: {
          Logger.debug(
              CdmCorpusDefinition.class.getSimpleName(),
              ctx,
              Logger.format("ObjectType not recognized: '{0}'", iObject.getObjectType().name())
          );
          break;
        }
      }

      return false;
    }, (iObject, path) -> {
      final CdmObjectType objectType = iObject.getObjectType();
      switch (objectType) {
        case ParameterDef: {
          // When a parameter has a data type that is a cdm object, validate that any default value
          // is the right kind object.
          final CdmParameterDefinition parameterDef = (CdmParameterDefinition) iObject;
          this.constTypeCheck(resOpt, currentDoc, parameterDef, null);
          break;
        }

        default: {
          Logger.debug(
              CdmCorpusDefinition.class.getSimpleName(),
              ctx,
              Logger.format("ObjectType not recognized: '{0}'", iObject.getObjectType().name())
          );
          break;
        }
      }

      return false;
    });

    resOpt.setIndexingDoc(null);
  }

  private void finishDocumentResolve(final CdmDocumentDefinition doc) {
    doc.setCurrentlyIndexing(false);
    doc.setImportsIndexed(true);
    doc.setNeedsIndexing(false);
    this.documentLibrary.markDocumentAsIndexed(doc);

    doc.getDefinitions().forEach(def -> {
      if (def.getObjectType() == CdmObjectType.EntityDef) {
        Logger.debug(CdmCorpusDefinition.class.getSimpleName(), this.ctx, Logger.format("indexed: '{0}'", def.getAtCorpusPath()));
      }
    });
  }

  private void resolveTraits(
      final CdmDocumentDefinition currentDoc,
      final ResolveOptions resOpt,
      final MutableInt entityNesting) {
    final MutableInt nesting = entityNesting;
    currentDoc.visit("", (iObject, path) -> {
      switch (iObject.getObjectType()) {
        case TraitDef:
        case PurposeDef:
        case DataTypeDef:
        case EntityDef:
        case AttributeGroupDef: {
          if (iObject.getObjectType() == CdmObjectType.EntityDef
              || iObject.getObjectType() == CdmObjectType.AttributeGroupDef) {
            nesting.increment();
            // Don't do this for entities and groups defined within entities since getting
            // traits already does that.
            if (nesting.getValue() > 1) {
              break;
            }
          }

          ((ResolveContext) this.ctx).setRelativePath(path);
          iObject.fetchResolvedTraits(resOpt);

          break;
        }
        case EntityAttributeDef:
        case TypeAttributeDef: {
          ((ResolveContext) this.ctx).setRelativePath(path);
          iObject.fetchResolvedTraits(resOpt);

          break;
        }
      }

      return false;
    }, (iObject, path) -> {
      if (iObject.getObjectType() == CdmObjectType.EntityDef
          || iObject.getObjectType() == CdmObjectType.AttributeGroupDef) {
        nesting.decrement();
      }

      return false;
    });

    entityNesting.setValue(nesting.getValue());
  }

  private void resolveForeignKeyReferences(
      final CdmDocumentDefinition currentDoc,
      final ResolveOptions resOpt,
      final MutableInt entityNesting) {
    final MutableInt nesting = entityNesting;
    currentDoc.visit("", (iObject, path) -> {
      final CdmObjectType ot = iObject.getObjectType();
      if (ot == CdmObjectType.AttributeGroupDef) {
        nesting.increment();
      }

      if (ot == CdmObjectType.EntityDef) {
        nesting.increment();
        if (nesting.getValue() == 1) {
          ((ResolveContext) this.ctx).setRelativePath(path);
          ((CdmEntityDefinition) iObject).fetchResolvedEntityReferences(resOpt);
        }
      }

      return false;
    }, (iObject, path) -> {
      if (iObject.getObjectType() == CdmObjectType.EntityDef
          || iObject.getObjectType() == CdmObjectType.AttributeGroupDef) {
        nesting.decrement();
      }

      return false;
    });

    entityNesting.setValue(nesting);
  }

  private void resolveAttributes(
      final CdmDocumentDefinition currentDoc,
      final ResolveOptions resOpt,
      final MutableInt entityNesting) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    final MutableInt nesting = entityNesting;
    currentDoc.visit("", (iObject, path) -> {
      final CdmObjectType ot = iObject.getObjectType();
      if (ot == CdmObjectType.EntityDef) {
        nesting.increment();
        if (nesting.getValue() == 1) {
          ctx.setRelativePath(path);
          iObject.fetchResolvedAttributes(resOpt);
        }
      }

      if (ot == CdmObjectType.AttributeGroupDef) {
        nesting.increment();
        if (nesting.getValue() == 1) {
          ctx.setRelativePath(path);
          iObject.fetchResolvedAttributes(resOpt);
        }
      }

      return false;
    }, (iObject, path) -> {
      if (iObject.getObjectType() == CdmObjectType.EntityDef
          || iObject.getObjectType() == CdmObjectType.AttributeGroupDef) {
        nesting.decrement();
      }

      return false;
    });

    entityNesting.setValue(nesting);
  }

  private void resolveReferencesTraitsArguments(
      final CdmDocumentDefinition currentDoc,
      final ResolveOptions resOpt,
      final MutableInt entityNesting) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    final Consumer<CdmObject> checkRequiredParamsOnResolvedTraits = obj -> {
      final ResolvedTraitSet rts = obj.fetchResolvedTraits(resOpt);
      if (rts != null) {
        for (int i = 0; i < rts.getSize(); i++) {
          final ResolvedTrait rt = rts.getSet().get(i);
          int found = 0;
          int resolved = 0;
          if (rt.getParameterValues() != null) {
            for (int iParam = 0; iParam < rt.getParameterValues().length(); iParam++) {
              if (rt.getParameterValues().fetchParameter(iParam).isRequired()) {
                found++;
                if (rt.getParameterValues().fetchValue(iParam) == null) {
                  String message = Logger.format(
                      "no argument supplied for required parameter '{0}' of trait '{1}' on '{2}'",
                      rt.getParameterValues().fetchParameter(iParam).getName(),
                      rt.getTraitName(),
                      obj.fetchObjectDefinition(resOpt).getName()
                  );
                  Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, message, currentDoc.getFolderPath() + ctx.getRelativePath());
                } else {
                  resolved++;
                }
              }
            }
          }
          if (found > 0 && found == resolved) {
            String message = Logger.format(
                "found and resolved '{0}' required parameters of trait '{1}' on '{2}'",
                 found,
                 rt.getTraitName(),
                 obj.fetchObjectDefinition(resOpt).getName()
            );
            Logger.info(CdmCorpusDefinition.class.getSimpleName(), ctx, message, currentDoc.getFolderPath() + ctx.getRelativePath());
          }
        }
      }
    };

    currentDoc.visit("", null, (iObject, path) -> {
      final CdmObjectType ot = iObject.getObjectType();
      if (ot == CdmObjectType.EntityDef) {
        ctx.setRelativePath(path);
        // get the resolution of all parameters and values through inheritance and defaults and arguments, etc.
        checkRequiredParamsOnResolvedTraits.accept(iObject);
        final CdmCollection<CdmAttributeItem> hasAttributeDefs = ((CdmEntityDefinition) iObject).getAttributes();
        // do the same for all attributes
        if (hasAttributeDefs != null) {
          for (final CdmAttributeItem attDef : hasAttributeDefs) {
            checkRequiredParamsOnResolvedTraits.accept(attDef);
          }
        }
      }
      if (ot == CdmObjectType.AttributeGroupDef) {
        ctx.setRelativePath(path);
        // get the resolution of all parameters and values through inheritance and defaults and arguments, etc.
        checkRequiredParamsOnResolvedTraits.accept(iObject);
        final CdmCollection<CdmAttributeItem> memberAttributeDefs = ((CdmAttributeGroupDefinition) iObject).getMembers();
        // do the same for all attributes
        if (memberAttributeDefs != null) {
          for (final CdmAttributeItem attDef : memberAttributeDefs) {
            checkRequiredParamsOnResolvedTraits.accept(attDef);
          }
        }
      }
      return false;
    });
  }

  private void resolveTraitArguments(
      final ResolveOptions resOpt,
      final CdmDocumentDefinition currentDoc) {
    final ResolveContext ctx = (ResolveContext) this.ctx;
    currentDoc.visit("", (iObject, path) -> {
      final CdmObjectType objectType = iObject.getObjectType();
      switch (objectType) {
        case TraitRef: {
          ctx.pushScope(iObject.fetchObjectDefinition(resOpt));
          break;
        }

        case ArgumentDef: {
          try {
            if (null != ctx.getCurrentScope().getCurrentTrait()) {
              ctx.setRelativePath(path);
              final ParameterCollection parameterCollection =
                  ctx.getCurrentScope().getCurrentTrait().fetchAllParameters(resOpt);
              Object aValue;

              if (objectType == CdmObjectType.ArgumentDef) {
                final CdmParameterDefinition paramFound = parameterCollection
                    .resolveParameter(ctx.getCurrentScope().getCurrentParameter(),
                        ((CdmArgumentDefinition) iObject).getName());
                ((CdmArgumentDefinition) iObject).setResolvedParameter(paramFound);
                aValue = ((CdmArgumentDefinition) iObject).getValue();

                // If parameter type is entity, then the value should be an entity or ref to one
                // same is true of 'dataType' data type.
                aValue = this.constTypeCheck(resOpt, currentDoc, paramFound, aValue);
                if (aValue != null) {
                  ((CdmArgumentDefinition) iObject).setValue(aValue);
                }
              }
            }
          } catch (final Exception e) {
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, e.getLocalizedMessage(), path);
            String message = Logger.format("Failed to resolve parameter on trait '{0}'", ctx.getCurrentScope().getCurrentTrait() != null ? ctx.getCurrentScope().getCurrentTrait().getName() : null);
            Logger.error(CdmCorpusDefinition.class.getSimpleName(), ctx, message, currentDoc.getFolderPath() + path);
          }

          ctx.getCurrentScope().setCurrentParameter(ctx.getCurrentScope().getCurrentParameter() + 1);
          break;
        }

        default: {
          Logger.debug(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("ObjectType not recognized: '{0}'", iObject.getObjectType().name()));
          break;
        }
      }

      return false;
    }, (iObject, path) -> {
      final CdmObjectType objectType = iObject.getObjectType();
      switch (objectType) {
        case TraitRef: {
          ((CdmTraitReference) iObject).resolvedArguments = true;
          ctx.popScope();
          break;
        }

        default: {
          Logger.debug(CdmCorpusDefinition.class.getSimpleName(), ctx, Logger.format("ObjectType not recognized: '{0}'", iObject.getObjectType().name()));
          break;
        }
      }

      return false;
    });
  }

  private void finishResolve() {
    final ResolveContext ctx = (ResolveContext) this.ctx;

    // Cleanup References.
    Logger.debug(CdmCorpusDefinition.class.getSimpleName(), ctx, "Finishing...");

    // Turn elevated traits back on, they are off by default and should work fully now that
    // everything is resolved.
    List<CdmDocumentDefinition> allDocuments = this.documentLibrary.listAllDocuments();
    for (final CdmDocumentDefinition doc : allDocuments) {
      this.finishDocumentResolve(doc);
    }
  }

  private boolean containsUnsupportedPathFormat(final String path) {
    final String statusMessage;
    if (path.startsWith("./") || path.startsWith(".\\")) {
      statusMessage = "The path should not start with ./";
    } else if (path.contains("../") || path.contains("..\\")) {
      statusMessage = "The path should not contain ../";
    } else if (path.contains("/./") || path.contains("\\.\\")) {
      statusMessage = "The path should not contain /./";
    } else {
      return false;
    }

    Logger.error(CdmCorpusDefinition.class.getSimpleName(), this.ctx, statusMessage);
    return true;
  }

  public StorageManager getStorage() {
    return this.storage;
  }

  public PersistenceLayer getPersistence() {
    return this.persistence;
  }

  public CdmCorpusContext getCtx() {
    return ctx;
  }

  public void setCtx(CdmCorpusContext ctx) {
    this.ctx = ctx;
  }

  public String getAppId() {
    return appId;
  }

  public void setAppId(final String appId) {
    this.appId = appId;
  }

  /**
   * @deprecated This function is extremely likely to be removed in the public interface, and not meant
   * to be called externally at all. Please refrain from using it.
   */
  @Deprecated
  public DocumentLibrary getDocumentLibrary() {
    return this.documentLibrary;
  }

  Map<String, SymbolSet> getDefinitionReferenceSymbols() {
    return definitionReferenceSymbols;
  }

  /**
   * Gets the last modified time of the object where it was readAsync from.
   */
  CompletableFuture<OffsetDateTime> computeLastModifiedTimeFromObjectAsync(
      final CdmObject currObject) {
    if (currObject instanceof CdmContainerDefinition) {
      final StorageAdapter adapter =
          this.storage.fetchAdapter(((CdmContainerDefinition) currObject).getNamespace());

      if (adapter == null) {
        Logger.error(
            CdmCorpusDefinition.class.getSimpleName(),
            this.ctx,
            Logger.format("Adapter not found for the Cdm object by ID {0}.", currObject.getId()),
            "computeLastModifiedTimeFromObjectAsync"
        );
        return null;
      }

      return adapter.computeLastModifiedTimeAsync(currObject.getAtCorpusPath());
    } else {
      return computeLastModifiedTimeFromObjectAsync(currObject.getInDocument());
    }
  }

  Map<String, ResolvedTraitSet> getEmptyRts() {
    return emptyRts;
  }

  void setEmptyRts(final Map<String, ResolvedTraitSet> emptyRts) {
    this.emptyRts = emptyRts;
  }

  /**
   * Gets the last modified time of the partition path without trying to read the file itself.
   *
   * @param corpusPath The corpus path
   * @return The last modified time
   */
  CompletableFuture<OffsetDateTime> computeLastModifiedTimeFromPartitionPathAsync(final String corpusPath) {
    // we do not want to load partitions from file, just check the modified times
    final Pair<String, String> pathTuple = this.storage.splitNamespacePath(corpusPath);
    final String nameSpace = pathTuple.getLeft();

    if (!StringUtils.isNullOrTrimEmpty(nameSpace)) {
      final StorageAdapter adapter = this.storage.fetchAdapter(nameSpace);

      if (adapter == null) {
        Logger.error(
            CdmCorpusDefinition.class.getSimpleName(),
            this.ctx,
            Logger.format("Adapter not found for the corpus path '{0}'", corpusPath),
            "computeLastModifiedTimeFromPartitionPathAsync"
        );
        return CompletableFuture.completedFuture(null);
      }
      return adapter.computeLastModifiedTimeAsync(corpusPath);
    }

    return CompletableFuture.completedFuture(null);
  }

  /**
   * Gets the last modified time of the object found at the input corpus path.
   * @param corpusPath The path to the object that you want to get the last modified time for
   * @return
   */
  CompletableFuture<OffsetDateTime> computeLastModifiedTimeAsync(final String corpusPath) {
    return this.computeLastModifiedTimeAsync(corpusPath, null);
  }

  /**
   * Gets the last modified time of the object found at the input corpus path.
   * @param corpusPath The path to the object that you want to get the last modified time for
   * @param obj
   * @return
   */
  CompletableFuture<OffsetDateTime> computeLastModifiedTimeAsync(
      final String corpusPath,
      final CdmObject obj) {
    return fetchObjectAsync(corpusPath, obj).thenCompose(currObject -> {
      if (currObject != null) {
        return this.computeLastModifiedTimeFromObjectAsync(currObject);
      }
      return CompletableFuture.completedFuture(null);
    });
  }

  @FunctionalInterface
  private interface ResolveAction {
    void invoke(CdmDocumentDefinition currentDoc, ResolveOptions resOptions, MutableInt entityNesting);
  }

  private class removeObjectCallBack implements VisitCallback {
    private final CdmCorpusDefinition thiz;
    private final ResolveContext ctx;
    private final CdmDocumentDefinition doc;

    public removeObjectCallBack(final CdmCorpusDefinition thiz, final ResolveContext ctx, final CdmDocumentDefinition doc) {
      this.thiz = thiz;
      this.ctx = ctx;
      this.doc = doc;
    }

    @Override
    public boolean invoke(final CdmObject iObject, final String path) {
      if (path.indexOf("(unspecified") > 0) {
        return true;
      }
      switch (iObject.getObjectType()) {
        case EntityDef:
        case ParameterDef:
        case TraitDef:
        case PurposeDef:
        case DataTypeDef:
        case TypeAttributeDef:
        case EntityAttributeDef:
        case AttributeGroupDef:
        case ConstantEntityDef:
        case AttributeContextDef:
        case LocalEntityDeclarationDef:
        case ReferencedEntityDeclarationDef:
          thiz.unRegisterSymbol(path, doc);
          thiz.unRegisterDefinitionReferenceSymbols(iObject, "rasb");
          break;
      }
      return false;
    }
  }
}
