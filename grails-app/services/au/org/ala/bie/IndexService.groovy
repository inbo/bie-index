package au.org.ala.bie

import au.org.ala.bie.search.IndexDocType
import au.org.ala.bie.search.IndexFieldDTO
import au.org.ala.bie.util.Encoder
import grails.config.Config
import grails.core.support.GrailsConfigurationAware
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.client.solrj.request.CollectionAdminRequest
import org.apache.solr.client.solrj.request.CoreAdminRequest
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.CoreAdminParams
import org.apache.solr.common.params.CursorMarkParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.params.SolrParams

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The interface to SOLR based logic.
 */
class IndexService implements GrailsConfigurationAware {
    def liveSolrClient
    def offlineSolrClient
    def updatingLiveSolrClient
    def adminSolrClient

    // Configuration
    SolrQuery searchTemplate
    SolrQuery suggestTemplate
    List<String> searchFq
    String searchDefType
    String searchBoost
    List<String> searchQf

    /** Socket timeout when building suggesters; a build can take substantially longer than a query */
    private static final long SUGGEST_BUILD_TIMEOUT = 60 * 60 * 1000L

    @Override
    void setConfiguration(Config config) {
        def search = config.solr.search
        searchBoost = search.boost
        searchDefType = search.defType
        searchTemplate = new SolrQuery()
        search.fq.each { searchTemplate.addFilterQuery(it) }
        searchTemplate.set('defType', searchDefType)
        searchTemplate.set('qf', search.qf.join(' '))
        search.bq.each { searchTemplate.add('bq', it) }
        searchTemplate.set('q.alt', search.qAlt)
        searchTemplate.set('boost', searchBoost)
        if (search.hl.hl as Boolean) {
            searchTemplate.highlightFields = (search.hl.fl as String).split(',')
            searchTemplate.highlightSimplePre = search.hl.simple.pre
            searchTemplate.highlightSimplePost = search.hl.simple.post
        }
        def suggest = config.solr.suggest
        suggestTemplate = new SolrQuery()
    }

    def deleteFromIndexByQuery(query, online){
        log.info("Deleting from index: " + query + "....")
        if (online) {
            updatingLiveSolrClient.deleteByQuery(query)
            updatingLiveSolrClient.commit()
        } else {
            offlineSolrClient.deleteByQuery(query)
            offlineSolrClient.commit()
        }
        log.info("Deleted from index: " + query)
    }

    /**
     * Delete the supplied index doc type from the index.
     * @param docType
     * @return
     */
    def deleteFromIndex(IndexDocType docType, boolean online) {
        log.info("Deleting from index: " + docType.name() + "....")
        def client = online ? updatingLiveSolrClient : offlineSolrClient
        client.deleteByQuery("idxtype:" + docType.name())
        log.info("Deleted from index: " + docType.name())
    }

    def swap() {
        log.info("Swapping index")
        CoreAdminRequest car = new CoreAdminRequest()
        car.setCoreName("bie")
        car.setOtherCoreName("bie-offline")
        car.setAction(CoreAdminParams.CoreAdminAction.SWAP)
        car.process(adminSolrClient.client)
    }

    def info() {
        CoreAdminRequest car = new CoreAdminRequest()
        car.setAction(CoreAdminParams.CoreAdminAction.STATUS)
        car.process(adminSolrClient.client)
    }

    /**
     * Index the supplied batch of docs.
     * @param docType
     * @param docsToIndex
     * @param offline Use the offline index (defaults to true)
     */
    def indexBatch(List docsToIndex, boolean online) throws Exception {
        def client = online ? updatingLiveSolrClient : offlineSolrClient
        def buffer = []

        //convert to SOLR input documents
        docsToIndex.each { map ->
            def solrDoc = new SolrInputDocument()
            map.each{ fieldName, fieldValue ->
                if(isList(fieldValue)){
                    fieldValue.each {
                        solrDoc.addField(fieldName, it)
                    }
                } else {
                    solrDoc.addField(fieldName, fieldValue)
                }
            }
            buffer << solrDoc
        }

        //add
        client.add(buffer)
        log.debug "Doing SOLR commit for ${buffer.size()} docs"
        //commit
        client.commit(true, false, true)

    }

    public Set<IndexFieldDTO> getIndexFieldDetails(String... fields) throws Exception {
        ModifiableSolrParams params = new ModifiableSolrParams()
        params.set('qt', '/admin/luke')
        params.set('tr', 'luke.xsl')
        if(fields != null) {
            params.set('fl', fields)
            params.set('numTerms', '1')
        } else {
            params.set('numTerms', '0')
        }

        QueryResponse response = liveSolrClient.query(params)
        Set<IndexFieldDTO> results = lukeResponseToIndexFieldDTOs(response, fields != null)
        return results
    }

    /**
     * Get a document by guid.
     *
     * @param guid The document guid
     * @param online True if using the online index
     *
     * @return The document or null for not found
     */
    SolrDocument getTaxonByGuid(String guid, boolean online) {
        def query = new SolrQuery("guid:${ Encoder.escapeSolr(guid) }")

        query.addFilterQuery("idxtype:${IndexDocType.TAXON.name()}")
        query.setStart(0)
        query.setRows(1)
        def response = this.query(query, online)
        return response.results.numFound == 0 ? null : response.results.get(0)
    }

    /**
     * Make a query to the index
     *
     * @param query The query
     * @param online Use the online/offline index
     *
     * @return The solr, which are essentially key->value maps
     */
    QueryResponse query(SolrQuery query, boolean online) {
        def client = online ? liveSolrClient : offlineSolrClient

        return client.query(query, SolrRequest.METHOD.POST)
    }

    /**
     * Query the index
     *
     * @param online Use the online index
     *
     * @param q The query
     * @param fq Any filter queries
     * @param rows The number of rows to get (default 10)
     * @param start The start position (default 0)
     * @param context Additional context parameters
     * @param sort sort field
     * @param dir Sort direction
     * @param cursor Cursor mark if paginating
     * @param useBoost Use the search boost functionality
     * @param useDefType Use the default defType
     *
     * @return The query result
     */
    QueryResponse query(boolean online, String q, List fq = [], Integer rows = 10, Integer start = 0, String context = null, String sort = null, String dir = 'asc', String cursor = null, boolean useBoost = false, boolean useDefType = false) {
        def query = new SolrQuery(q)

        if (context) {
            query.add(context(context))
        }
        if (fq) {
            fq.each { query.addFilterQuery(it) }
        }
        if (rows) {
            query.setRows(rows)
        }
        if (start) {
            query.setStart(start)
        }
        if (sort) {
            query.sort = new SolrQuery.SortClause(sort, dir == 'desc' ? SolrQuery.ORDER.desc : SolrQuery.ORDER.asc)
        }
        if (cursor) {
            query.set(CursorMarkParams.CURSOR_MARK_PARAM, cursor)
        }
        if (useBoost) {
            query.set('boost', searchBoost)
        }
        if (useDefType) {
            query.set("defType", searchDefType)
        }
        return this.query(query, online)
    }

    /**
     * Get context from a context string in the form of solr params.
     *
     * @param context
     * @return
     */
    SolrParams context(String context) {
        if (!context)
            return null
        ModifiableSolrParams params = context.split('(?<!\\\\)&').inject(new ModifiableSolrParams(), { sedd, param ->
            def kv = param.split('=', 2)
            seed.add(kv[0], kv[1])
            sedd
        })
        return params
    }

    /**
     * Do a solr search with standard parameters
     *
     * @param online Use the online index
     * @param q The query
     * @param fqs A list of additional filter queries (empty by default)
     * @param facets A list of facets (empty by default)
     * @param start The start position
     * @param rows The number of rows to retrieve
     * @param sort The field to sort on
     * @param dir The sort direction
     *
     * @return
     */
    QueryResponse search(boolean online, String q, List fqs = [], List facets = [], Integer start = 0, Integer rows = 10, sort = null, dir = SolrQuery.ORDER.asc) {
        SolrQuery query = searchTemplate.getCopy()

        query.query = q
        if (!dir || !(dir instanceof SolrQuery.ORDER))
            dir = dir == 'desc' ? SolrQuery.ORDER.desc : SolrQuery.ORDER.asc
        fqs.each { query.addFilterQuery(it) }
        // Add query parameters
        if (facets) {
            query.facet = true
            query.facetMinCount = 1
            facets.each { query.addFacetField(it) }
        }
        if (start)
            query.start = start
        if (rows)
            query.rows = rows
        if (sort)
            query.sort = new SolrQuery.SortClause(sort, dir)
        return this.query(query, online)
    }

    /**
     * Query suggestions from the index
     *
     * @param online Use the online index
     * @param q The query
     * @param fq Any filter queries
     * @param rows The number of rows to get (default 10)
     * @param start The start position (default 0)
     * @param context Additional context parameters
     *
     * @return The suggestion response (results in groupResponse)
     */
    QueryResponse suggest(boolean online, String q, String idxtype, Integer rows = 10, Integer start = 0, String context = null) {
        def query = suggestTemplate.getCopy()

        query.setRequestHandler('/suggest')
        query.set('suggest.q', q)
        if (context)
            query.add(context(context))
        if (idxtype)
            query.set('suggest.cfq', idxtype)
        if (rows)
            query.set('suggest.count', rows)
        if (start)
            query.setStart(start)
        return this.query(query, online)
    }

    /**
     * Build the suggestion index
     * <p>
     * A single distributed suggest.build request only builds the suggester on the one
     * replica per shard that the coordinating node picks, leaving the other replicas
     * unbuilt (they then throw "suggester was not built" on every autocomplete request
     * that lands on them). So look up every active core of the collection and build
     * each one directly, one replica per shard at a time, so that a replica of every
     * shard remains available while its sibling is being built.
     *
     * @param online Use the online index
     * @param progress Called with (core name, cores completed, total cores) as each core completes
     *
     * @return The build responses, keyed by core name
     */
    Map<String, QueryResponse> buildSuggestIndex(boolean online, Closure progress = null) {
        def connection = online ? liveSolrClient.connection : offlineSolrClient.connection
        def collection = connection.tokenize('/').last()
        def cores = collectionCores(collection)
        if (!cores)
            throw new IllegalStateException("No active cores found for collection ${collection}")
        def shardGroups = coresByShard(cores).values()
        // Builds can run far longer than the normal query timeout, so use a dedicated client
        def client = new HttpSolrClient.Builder(adminSolrClient.connection)
            .withSocketTimeout(SUGGEST_BUILD_TIMEOUT)
            .build()
        def pool = Executors.newFixedThreadPool(shardGroups.size())
        def results = new ConcurrentHashMap<String, Object>()
        def completed = new AtomicInteger()
        try {
            def futures = shardGroups.collect { group ->
                pool.submit({
                    group.each { core ->
                        def query = suggestTemplate.getCopy()
                        query.setRequestHandler('/suggest')
                        query.set('suggest.build', "true")
                        query.set(CommonParams.DISTRIB, "false")
                        try {
                            results.put(core, client.query(core, query))
                        } catch (Exception ex) {
                            log.error("Unable to build suggester on core ${core}", ex)
                            results.put(core, ex)
                        }
                        progress?.call(core, completed.incrementAndGet(), cores.size())
                    }
                } as Callable)
            }
            futures.each { it.get() }
        } finally {
            pool.shutdown()
            client.close()
        }
        def failed = results.findAll { it.value instanceof Exception }
        if (failed)
            throw new IllegalStateException("Unable to build suggester on ${failed.keySet().join(', ')}; see bie-index logs for details")
        return results as Map<String, QueryResponse>
    }

    /**
     * Get the active cores of the named collection from the cluster status.
     * Collection aliases are resolved by Solr, so an alias name can be supplied.
     *
     * @param collection The collection or alias name
     *
     * @return The core names of the active replicas
     */
    List<String> collectionCores(String collection) {
        def response = new CollectionAdminRequest.ClusterStatus().setCollectionName(collection).process(adminSolrClient.client)
        def cores = activeCores(response.getResponse().get('cluster'))
        if (cores == null)
            throw new IllegalStateException("Collection ${collection} not found in cluster status")
        log.info("Suggester build targets for ${collection}: ${cores}")
        return cores
    }

    /**
     * Extract the active core names of the collection(s) in a cluster status response.
     * <p>
     * Solr resolves the collection parameter of a CLUSTERSTATUS request before
     * building the response, so the collections it contains are exactly the
     * collection(s) that the requested name or alias refers to. (The per-collection
     * 'aliases' property is only present when the request names the collection
     * directly, so it cannot be used to match an alias.) The cluster section of the
     * response is a NamedList, while the collection, shard and replica details below
     * it are plain maps parsed from the cluster state JSON. Replicas record the core
     * name in their 'core' property; the map key is the core_node znode name.
     *
     * @param cluster The cluster section of a CLUSTERSTATUS response
     *
     * @return The core names of the active replicas, or null if there are no collections
     */
    static List<String> activeCores(cluster) {
        def collections = cluster?.get('collections')
        if (collections == null || collections.size() == 0)
            return null
        def cores = []
        for (def i = 0; i < collections.size(); i++) {
            def details = collections.getVal(i)
            (details.shards ?: [:]).values().each { shard ->
                def replicas = shard.replicas ?: [:]
                cores.addAll(replicas.values().findAll { it.state == 'active' }.collect { it.core ?: it.name })
            }
        }
        return cores
    }

    /**
     * Group core names by shard, so that replicas of the same shard can be built one
     * after the other (bie-efs-a_shard6_replica_n22 is grouped under bie-efs-a_shard6)
     */
    static Map<String, List<String>> coresByShard(List<String> cores) {
        return cores.groupBy { core -> core.replaceAll('_replica_.*$', '') }
    }

    private boolean isList(object) {
        [Collection, Object[]].any { it.isAssignableFrom(object.getClass()) }
    }

    /**
     * parses the response string from the service that returns details about the indexed fields
     * @param response
     * @param includeCounts
     * @return
     */
    private Set<IndexFieldDTO> lukeResponseToIndexFieldDTOs(QueryResponse response, boolean includeCounts) {
        Set<IndexFieldDTO> fieldList = includeCounts ? new LinkedHashSet<IndexFieldDTO>() : new TreeSet<IndexFieldDTO>()

        // This is actually a SOLR SimpleOrderedMap
        Iterable<Map.Entry<String,Object>> fields = (Iterable<Map.Entry<String,Object>>) response.response['fields']

        fieldList.addAll(fields.collect {
            def name = it.key
            String schema = it.value['schema']
            String type = it.value['type']
            String distinctString = it.value['distinct']
            Integer distinct
            if (distinctString) {
                try {
                    distinct = Integer.valueOf(distinctString)
                } catch (e) {
                    distinct = null
                }
            } else {
                distinct = null
            }
            new IndexFieldDTO(name: name, dataType: type, indexed: schema.contains('I'), stored: schema.contains('S'), numberDistinctValues: distinct)
        })

        return fieldList
    }
}
