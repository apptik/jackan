/* 
 * Copyright 2015 Trento Rise  (trentorise.eu) 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.trentorise.opendata.jackan.ckan;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Charsets;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.base.Strings;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;

import eu.trentorise.opendata.jackan.JackanException;
import eu.trentorise.opendata.jackan.SearchResults;
import eu.trentorise.opendata.commons.OdtUtils;
import static eu.trentorise.opendata.commons.validation.Preconditions.checkNotEmpty;
import static eu.trentorise.opendata.commons.OdtUtils.removeTrailingSlash;

import java.io.*;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.entity.ContentType;

/**
 * Client to access a ckan instance. Threadsafe.
 *
 * The client is a thin wrapper upon Ckan api, thus one method call should
 * correspond to only one web api call. This means sometimes to get a full
 * object from Ckan, you will need to do a second call (for example, calling
 * {@link #getDataset(java.lang.String)} will also return its resources because
 * Ckan sends them with the dataset, but to be sure to have all the fields of a
 * resource you will need to call {@link #getResource(java.lang.String)).
 *
 * For writing to Ckan you might want to use {@link CheckedCkanClient} which does additional checks to ensure written content is correct.
 *
 * @author David Leoni, Ivan Tankoyeu
 *
 */
public class CkanClient {

    /**
     * CKAN uses timestamps like '1970-01-01T01:00:00.000010' in UTC timezone,
     * has precision up to microsecond and doesn't append 'Z' to timestamps. The
     * format respects
     * <a href="https://en.wikipedia.org/wiki/ISO_8601" target="_blank">ISO 8601
     * standard</a>. In Jackan we store it as {@link java.sql.Timestamp} or
     * {@code null} if parse is not successful.
     *
     * @see #parseTimestamp(java.lang.String)
     * @see #formatTimestamp(java.sql.Timestamp)
     */
    public static final String CKAN_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS";

    /**
     * Found pattern "2013-12-17T00:00:00" in resource.date_modified in
     * dati.toscana:
     * http://dati.toscana.it/api/3/action/package_show?id=alluvioni_bacreg See
     * also  <a href="https://github.com/ckan/ckan/issues/1874"> ckan issue 874
     * </a> and
     * <a href="https://github.com/ckan/ckan/pull/2519">ckan pull 2519</a>
     */
    public static final String CKAN_NO_MILLISECS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    /**
     * Sometimes we get back Python "None" as a string instead of proper JSON
     * null
     */
    public static final String NONE = "None";

    @Nullable
    private static ObjectMapper objectMapper;

    @Nullable
    private static ObjectMapper objectMapperForPosting;

    /**
     * Notice that even for the same api version (at least for versions <= 3)
     * different CKAN instances can behave quite differently (sic)
     */
    public static final ImmutableList<Integer> SUPPORTED_API_VERSIONS = ImmutableList.of(3);

    private final String catalogURL;

    @Nullable
    private final String ckanToken;

    private static final Logger LOG = Logger.getLogger(CkanClient.class.getName());

    @Nullable
    private HttpHost proxy;

    /**
     * Returns a clone of the json object mapper used internally for read
     * operations
     *
     */
    public static ObjectMapper getObjectMapperClone() {
        return getObjectMapper().copy();
    }

    /**
     * Returns a clone of the json object mapper used internally for create
     * operations.
     *
     */
    public static ObjectMapper getObjectMapperForPostingClone() {
        return getObjectMapperForPosting().copy();
    }

    @JsonSerialize(as = CkanResourceBase.class)
    private static abstract class CkanResourceForPosting {
    }

    @JsonSerialize(as = CkanDatasetBase.class)
    private static abstract class CkanDatasetForPosting {
    }

    @JsonSerialize(as = CkanGroupOrgBase.class)
    private static abstract class CkanGroupOrgForPosting {
    }

    /**
     * Retrieves the Jackson object mapper configured for creation/update
     * operations. Internally, Object mapper is initialized at first call.
     */
    static ObjectMapper getObjectMapperForPosting() {
        if (objectMapperForPosting == null) {
            objectMapperForPosting = getObjectMapperClone();

            // when posting for creating datasets sending too much null stuff seems to confuse Ckan.
            objectMapperForPosting.setSerializationInclusion(Include.NON_NULL);
            objectMapperForPosting.addMixInAnnotations(CkanResource.class, CkanResourceForPosting.class);
            objectMapperForPosting.addMixInAnnotations(CkanDataset.class, CkanDatasetForPosting.class);
            objectMapperForPosting.addMixInAnnotations(CkanOrganization.class, CkanGroupOrgForPosting.class);
        }
        return objectMapperForPosting;
    }

    /**
     * Retrieves the Jackson object mapper. Internally, Object mapper is
     * initialized at first call.
     */
    static ObjectMapper getObjectMapper() {

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper
                    .setPropertyNamingStrategy(
                            PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES)
                    .configure(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false) // let's be tolerant
                    .configure(
                            MapperFeature.USE_GETTERS_AS_SETTERS,
                            false) // not good for unmodifiable collections, if we will ever use any
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            // When reading dates, Jackson defaults to using GMT for all processing unless specifically told otherwise, see http://wiki.fasterxml.com/JacksonFAQTimestampHandling
            // When writing dates, Jackson would add a Z for timezone by CKAN doesn't use it, i.e.  "2013-11-11T04:12:11.110868"  so we removed it here
            // Jackan will also add +1 for GMT... sic, better to use a custom module, see the following. 
            objectMapper.registerModule(new CkanJacksonModule());

            objectMapper.registerModule(new GuavaModule());

        }
        return objectMapper;
    }

    /**
     * Creates a Ckan client with null token and proxy
     *
     * @param url the catalog url i.e. http://data.gov.uk
     */
    public CkanClient(String url) {
        this(url, null, null);
    }

    /**
     * Creates a Ckan client with null proxy.
     *
     * @param URL the catalog url i.e. http://data.gov.uk. Internally, it will
     * be stored in a normalized format (to avoid i.e. trailing slashes).
     * @param token the private token string for ckan repository
     */
    public CkanClient(String URL, @Nullable String token) {
        this(URL, token, null);
    }

    /**
     * Creates a Ckan client.
     *
     * @param URL the catalog url i.e. http://data.gov.uk. Internally, it will
     * be stored in a normalized format (to avoid i.e. trailing slashes).
     * @param token the private token string for ckan repository
     * @param proxy the proxy used to perform GET and POST calls
     */
    public CkanClient(String URL, @Nullable String token, @Nullable HttpHost proxy) {
        checkNotEmpty(URL, "invalid ckan catalog url");
        this.catalogURL = removeTrailingSlash(URL);
        this.ckanToken = token;
        this.proxy = proxy;
    }

    @Override
    public String toString() {
        String maskedToken = ckanToken == null ? null : "*****MASKED_TOKEN*******";
        return "CkanClient{" + "catalogURL=" + catalogURL + ", ckanToken=" + maskedToken + '}';
    }

    /**
     * Calculates a full url out of the provided params
     *
     * @param path something like /api/3/package_show
     * @param params list of key, value parameters. They must be not be url
     * encoded. i.e. "id","laghi-monitorati-trento"
     * @return the full url to be called.
     * @throws JackanException if there is any error building the url
     */
    private String calcFullUrl(String path, Object[] params) {
        checkNotNull(path);

        try {
            StringBuilder sb = new StringBuilder().append(catalogURL).append(path);
            for (int i = 0; i < params.length; i += 2) {
                sb.append(i == 0 ? "?" : "&")
                        .append(URLEncoder.encode(params[i].toString(), "UTF-8"))
                        .append("=")
                        .append(URLEncoder.encode(params[i + 1].toString(),
                                        "UTF-8"));
            }
            return sb.toString();
        }
        catch (Exception ex) {
            throw new JackanException("Error while building url to perform GET! \n path: " + path + " \n params: " + Arrays.toString(params), ex);
        }
    }

    /**
     * Method for http GET
     *
     * @param <T>
     * @param responseType a descendant of CkanResponse
     * @param path something like /api/3/package_show
     * @param params list of key, value parameters. They must be not be url
     * encoded. i.e. "id","laghi-monitorati-trento"
     * @throws JackanException on error
     */
    <T extends CkanResponse> T getHttp(Class<T> responseType, String path,
            Object... params) {
        checkNotNull(responseType);
        checkNotNull(path);

        String fullUrl = calcFullUrl(path, params);

        T dr;
        String returnedText;

        try {
            LOG.log(Level.FINE, "getting {0}", fullUrl);
            Request request = Request.Get(fullUrl);
            if (ckanToken != null) {
                request.addHeader("Authorization", ckanToken);
            }
            if (proxy != null) {
                request.viaProxy(proxy);
            }
            Response response = request.execute();

            InputStream stream = response.returnResponse().getEntity().getContent();

            try (InputStreamReader reader = new InputStreamReader(stream, Charsets.UTF_8)) {
                returnedText = CharStreams.toString(reader);
            }
        }
        catch (Exception ex) {
            throw new JackanException("Error while performing GET. Request url was: " + fullUrl, ex);
        }
        try {
            dr = getObjectMapper().readValue(returnedText, responseType);
        }
        catch (Exception ex) {
            throw new JackanException("Couldn't interpret json returned by the server! Returned text was: " + returnedText, ex);
        }

        if (!dr.success) {
            throw new JackanException(
                    "Error while performing GET. Request url was: " + fullUrl,
                    dr.error, this);

        }
        return dr;

    }

    /**
     *
     * @param <T>
     * @param responseType a descendant of CkanResponse
     * @param path something like 1/api/3/action/package_create
     * @param body the body of the POST
     * @param the content type, i.e.
     * @param params list of key, value parameters. They must be not be url
     * encoded. i.e. "id","laghi-monitorati-trento"
     * @throws JackanException on error
     */
    <T extends CkanResponse> T postHttp(Class<T> responseType, String path, String body, ContentType contentType,
            Object... params) {
        checkNotNull(responseType);
        checkNotNull(path);
        checkNotNull(body);
        checkNotNull(contentType);

        String fullUrl = calcFullUrl(path, params);

        T dr;
        String returnedText;

        try {
            LOG.log(Level.FINE, "Posting to url {0}", fullUrl);
            LOG.log(Level.FINE, "Sending body:{0}", body);
            Request request = Request.Post(fullUrl);
            if (proxy != null) {
                request.viaProxy(proxy);
            }
            Response response = request.bodyString(body, contentType).addHeader("Authorization", ckanToken).execute();

            InputStream stream = response.returnResponse().getEntity().getContent();

            try (InputStreamReader reader = new InputStreamReader(stream, Charsets.UTF_8)) {
                returnedText = CharStreams.toString(reader);
            }
        }
        catch (Exception ex) {
            throw new JackanException("Error while performing a POST! Request url is:" + fullUrl, ex);
        }

        try {
            dr = getObjectMapper().readValue(returnedText, responseType);
        }
        catch (Exception ex) {
            throw new JackanException("Couldn't interpret json returned by the server! Returned text was: " + returnedText, ex);
        }

        if (!dr.success) {
            throw new JackanException(
                    "Error while performing a POST! Request url is:" + fullUrl,
                    dr.error, this
            );
        }
        return dr;

    }

    /**
     * Returns the catalog URL (normalized).
     */
    public String getCatalogURL() {
        return catalogURL;
    }

    /**
     * Returns the private CKAN token.
     */
    public String getCkanToken() {
        return ckanToken;
    }

    /**
     * Returns the URL of dataset page in the catalog website.
     *
     * Valid URLs have this format with the name:
     * http://dati.trentino.it/dataset/impianti-di-risalita-vivifiemme-2013
     *
     * @param datasetIdOrName either of name the dataset (preferred) or the
     * alphanumerical id.
     *
     * @param catalogUrl i.e. http://dati.trentino.it
     */
    public static String makeDatasetURL(String catalogUrl, String datasetIdOrName) {
        checkNotEmpty(catalogUrl, "invalid catalog url");
        checkNotEmpty(datasetIdOrName, "invalid dataset identifier");
        return removeTrailingSlash(catalogUrl) + "/dataset/" + datasetIdOrName;
    }

    /**
     *
     * Returns the URL of resource page in the catalog website.
     *
     * Valid URLs have this format with the dataset name
     * 'impianti-di-risalita-vivifiemme-2013':
     * http://dati.trentino.it/dataset/impianti-di-risalita-vivifiemme-2013/resource/779d1d9d-9370-47f4-a194-1b0328c32f02
     *
     * @param catalogUrl i.e. http://dati.trentino.it
     * @param datasetIdOrName the dataset name (preferred) or the alphanumerical
     * id
     *
     * @param resourceId the alphanumerical id of the resource (DON'T use
     * resource name)
     */
    public static String makeResourceURL(String catalogUrl, String datasetIdOrName, String resourceId) {
        checkNotEmpty(catalogUrl, "invalid catalog url");
        checkNotEmpty(datasetIdOrName, "invalid dataset identifier");
        checkNotEmpty(resourceId, "invalid resource id");
        return OdtUtils.removeTrailingSlash(catalogUrl)
                + "/" + datasetIdOrName + "/resource/" + resourceId;
    }

    /**
     *
     * Given some group parameters, reconstruct the URL of group page in the
     * catalog website.
     *
     * Valid URLs have this format with the group name
     * 'gestione-del-territorio':
     *
     * http://dati.trentino.it/group/gestione-del-territorio
     *
     * @param catalogUrl i.e. http://dati.trentino.it
     * @param groupNameOrId the group name as in {@link CkanGroup#getName()}
     * (preferred), or the group's alphanumerical id.
     */
    public static String makeGroupURL(String catalogUrl, String groupNameOrId) {
        checkNotEmpty(catalogUrl, "invalid catalog url");
        checkNotEmpty(groupNameOrId, "invalid dataset identifier");
        return OdtUtils.removeTrailingSlash(catalogUrl) + "/group/" + groupNameOrId;
    }

    /**
     * Returns list of dataset names like i.e. limestone-pavement-orders
     *
     * @throws JackanException on error
     */
    public synchronized List<String> getDatasetList() {
        return getHttp(DatasetListResponse.class, "/api/3/action/package_list").result;
    }

    /**
     *
     * @param limit
     * @param offset Starts with 0 included. getDatasetList(1,0) will return
     * exactly one dataset, if catalog is not empty.
     * @return list of data names like i.e. limestone-pavement-orders
     * @throws JackanException on error
     */
    public synchronized List<String> getDatasetList(Integer limit,
            Integer offset) {
        return getHttp(DatasetListResponse.class, "/api/3/action/package_list",
                "limit", limit, "offset", offset).result;
    }

    /**
     * Returns the list of available licenses in the ckan catalog.
     */
    public synchronized List<CkanLicense> getLicenseList() {
        return getHttp(LicenseListResponse.class, "/api/3/action/license_list").result;
    }

    /**
     * Returns the latest api version supported by the catalog
     *
     * @throws JackanException on error
     */
    public synchronized int getApiVersion() {
        for (int i = 3; i >= 1; i--) {
            return getApiVersion(i); // this is demential. But /api always gives { "version": 1} ....
        }
        throw new JackanException("Error while getting api version!", this);
    }

    /**
     * Returns the latest api version supported by the catalog
     *
     * @throws JackanException on error
     */
    private synchronized int getApiVersion(int number) {
        String fullUrl = catalogURL + "/api/" + number;
        LOG.log(Level.FINE, "getting {0}", fullUrl);
        try {
            Request request = Request.Get(fullUrl);
            if (proxy != null) {
                request.viaProxy(proxy);
            }
            String json = request.execute().returnContent()
                    .asString();

            return getObjectMapper().readValue(json, ApiVersionResponse.class
            ).version;
        }
        catch (Exception ex) {
            throw new JackanException("Error while fetching api version!", this, ex);
        }

    }

    /**
     * @param idOrName either the dataset name (i.e. laghi-monitorati-trento) or
     * the the alphanumerical id (i.e. 96b8aae4e211f3e5a70cdbcbb722264256ae2e7d)
     *
     * @throws JackanException on error
     */
    public synchronized CkanDataset
            getDataset(String idOrName) {
        CkanDataset cd = getHttp(DatasetResponse.class, "/api/3/action/package_show",
                "id", idOrName).result;
        for (CkanResource cr
                : cd.getResources()) {
            cr.setPackageId(cd.getId());
        }
        return cd;
    }

    /**
     * @throws JackanException on error
     */
    public synchronized List<CkanUser> getUserList() {
        return getHttp(UserListResponse.class, "/api/3/action/user_list").result;
    }

    /**
     * @param id i.e. 'admin'
     * @throws JackanException on error
     */
    public synchronized CkanUser
            getUser(String id) {
        return getHttp(UserResponse.class, "/api/3/action/user_show", "id", id).result;
    }

    /**
     * @param id The alphanumerical id of the resource, such as
     * d0892ada-b8b9-43b6-81b9-47a86be126db.
     *
     * @throws JackanException on error
     */
    public synchronized CkanResource
            getResource(String id) {
        return getHttp(ResourceResponse.class, "/api/3/action/resource_show",
                "id", id).result;
    }

    /**
     * Returns the groups present in Ckan.
     *
     * Notice that organizations will <i>not</i> be returned by this method. To
     * get them, use {@link #getOrganizationList() } instead.
     *
     * @throws JackanException on error
     */
    public synchronized List<CkanGroup> getGroupList() {
        return getHttp(GroupListResponse.class, "/api/3/action/group_list",
                "all_fields", "True").result;
    }

    /**
     * Return group names, like i.e. management-of-territory
     *
     * @throws JackanException on error
     */
    public synchronized List<String> getGroupNames() {
        return getHttp(GroupNamesResponse.class, "/api/3/action/group_list").result;
    }

    /**
     * Returns a Ckan group. Do not pass an organization id, to get organization
     * use {@link #getOrganization(java.lang.String) } instead.
     *
     * @param idOrName either the group name (i.e. hospitals-in-trento-district)
     * or the group alphanumerical id
     * @throws JackanException on error
     */
    public synchronized CkanGroup
            getGroup(String idOrName) {
        return getHttp(GroupResponse.class, "/api/3/action/group_show", "id",
                idOrName, "include_datasets", "false").result;
    }

    /**
     * Returns the organizations present in CKAN.
     *
     * @see #getGroupList()
     *
     * @throws JackanException on error
     */
    public synchronized List<CkanOrganization> getOrganizationList() {
        return getHttp(OrganizationListResponse.class, "/api/3/action/organization_list",
                "all_fields", "True").result;
    }

    /**
     * Returns all the resource formats available in the catalog.
     *
     * @throws JackanException on error
     */
    public synchronized Set<String> getFormats() {
        return getHttp(FormatListResponse.class, "/api/3/action/format_autocomplete", "q", "", "limit", "1000").result;
    }

    /**
     * @throws JackanException on error
     */
    public synchronized List<String> getOrganizationNames() {
        return getHttp(GroupNamesResponse.class, "/api/3/action/organization_list").result;
    }

    /**
     * Returns a Ckan organization.
     *
     * @param organizationIdOrName either the name of organization (i.e.
     * culture-and-education) or the alphanumerical id (i.e.
     * 232cad97-ecf2-447d-9656-63899023887f). Do not pass it a group id.
     * @throws JackanException on error
     */
    public synchronized CkanOrganization
            getOrganization(String organizationIdOrName) {
        return getHttp(OrganizationResponse.class, "/api/3/action/organization_show", "id",
                organizationIdOrName, "include_datasets", "false").result;
    }

    /**
     * Returns a list of tags names, i.e. "gp-practice-earnings","Aid Project
     * Evaluation", "tourism-satellite-account". We think names SHOULD be
     * lowercase with minuses instead of spaces, but in some cases they aren't.
     *
     * @throws JackanException on error
     */
    public synchronized List<CkanTag> getTagList() {
        return getHttp(TagListResponse.class, "/api/3/action/tag_list",
                "all_fields", "True").result;
    }

    /**
     * Returns tags containing the string given in query.
     *
     * @param query
     * @throws JackanException on error
     */
    public synchronized List<String> getTagNamesList(String query) {
        return getHttp(TagNamesResponse.class, "/api/3/action/tag_list",
                "query", query).result;
    }

    /**
     * @throws JackanException on error
     */
    public synchronized List<String> getTagNamesList() {
        return getHttp(TagNamesResponse.class, "/api/3/action/tag_list").result;
    }

    /**
     * Search datasets containing provided text in the metadata
     *
     * @param text The query string
     * @param limit maximum results to return
     * @param offset search begins from offset. Starts from 0, so that offset 0
     * limit 1 returns exactly 1 result, if there is a matching dataset)
     * @throws JackanException on error
     */
    public synchronized SearchResults<CkanDataset> searchDatasets(String text,
            int limit, int offset) {
        return searchDatasets(CkanQuery.filter().byText(text), limit, offset);
    }

    /**
     * @param fqPrefix either "" or " AND "
     * @param list list of names of ckan objects
     */
    private static String appendNamesList(String fqPrefix, String key, List<String> list, StringBuilder fq) {
        if (list.size() > 0) {
            fq.append(fqPrefix)
                    .append("(");
            String prefix = "";
            for (String n : list) {
                fq.append(prefix).append(key).append(":");
                fq.append('"' + n + '"');
                prefix = " AND ";
            }
            fq.append(")");
            return " AND ";
        } else {
            return "";
        }

    }

    /**
     * Parses a Ckan timestamp into a Java Timestamp.
     *
     * @throws IllegalArgumentException if timestamp can't be parsed.
     * @see #formatTimestamp(java.sql.Timestamp) for the inverse process.
     */
    public static Timestamp parseTimestamp(String timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Found null timestamp!");
        }

        if (NONE.equals(timestamp)) {
            throw new IllegalArgumentException("Found timestamp with 'None' inside!");
        }

        return Timestamp.valueOf(timestamp.replace("T", " "));
    }

    /**
     * Formats a timestamp according to {@link #CKAN_TIMESTAMP_PATTERN}, with
     * precision up to microseconds.
     *
     * @see #parseTimestamp(java.lang.String) for the inverse process.
     */
    @Nullable
    public static String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Found null timestamp!");
        }
        Timestamp ret = Timestamp.valueOf(timestamp.toString());
        ret.setNanos((timestamp.getNanos() / 1000) * 1000);
        return Strings.padEnd(ret.toString().replace(" ", "T"), "1970-01-01T01:00:00.000001".length(), '0');
    }

    /**
     * @params s a string to encode in a format suitable for URLs.
     */
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replaceAll("\\+", "%20");
        }
        catch (UnsupportedEncodingException ex) {
            throw new JackanException("Unsupported encoding", ex);
        }
    }

    /**
     * Search datasets according to the provided query.
     *
     * @param query The query object
     * @param limit maximum results to return
     * @param offset search begins from offset
     * @throws JackanException on error
     */
    public synchronized SearchResults<CkanDataset> searchDatasets(
            CkanQuery query,
            int limit,
            int offset
    ) {

        StringBuilder params = new StringBuilder();

        params.append("rows=").append(limit)
                .append("&start=").append(offset);

        if (query.getText().length() > 0) {
            params.append("&q=");
            params.append(urlEncode(query.getText()));
        }

        StringBuilder fq = new StringBuilder();
        String fqPrefix = "";

        fqPrefix = appendNamesList(fqPrefix, "groups", query.getGroupNames(), fq);
        fqPrefix = appendNamesList(fqPrefix, "organization", query.getOrganizationNames(), fq);
        fqPrefix = appendNamesList(fqPrefix, "tags", query.getTagNames(), fq);
        fqPrefix = appendNamesList(fqPrefix, "license_id", query.getLicenseIds(), fq);

        if (fq.length() > 0) {
            params.append("&fq=")
                    .append(urlEncode(fq.insert(0, "(").append(")").toString()));
        }

        DatasetSearchResponse dsr;
        dsr
                = getHttp(DatasetSearchResponse.class,
                        "/api/3/action/package_search?" + params.toString());

        if (dsr.success) {
            for (CkanDataset ds : dsr.result.getResults()) {
                for (CkanResource cr : ds.getResources()) {
                    cr.setPackageId(ds.getId());
                }
            }
        }

        return dsr.result;
    }

    /**
     * Creates ckan resource on the server.
     *
     * @param resource ckan resource object with the minimal set of parameters
     * required. See
     * {@link CkanResource#CkanResource(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)}
     * @return the newly created resource
     * @throws JackanException
     */
    public synchronized CkanResource createResource(CkanResourceBase resource) {

        if (ckanToken == null) {
            throw new JackanException("Tried to create resource" + resource.getName() + ", but ckan token was not set!");
        }

        ObjectMapper om = CkanClient.getObjectMapperForPosting();
        String json = null;
        try {
            json = om.writeValueAsString(resource);
        }
        catch (IOException e) {
            throw new JackanException("Couldn't serialize the provided CkanResource!", e);

        }
        return postHttp(ResourceResponse.class, "/api/3/action/resource_create", json, ContentType.APPLICATION_JSON).result;
    }

    /**
     * Checks dataset can actually be created
     *
     * @throws IllegalArgumentException if minimal requirements aren't met
     */
    static void checkDataset(CkanDataset dataset) {
        checkNotEmpty(dataset.getName(), "invalid ckan dataset name (must have no spaces and dashes as separators, i.e. \"limestone-pavement-orders");
        checkNotEmpty(dataset.getUrl(), "invalid ckan dataset url to description page");
        checkNotNull(dataset.getExtras(), "invalid ckan dataset extras");
    }

    /**
     * Checks if the provided resource meets the requirements to be created to
     * CKAN.
     *
     * @throws IllegalArgumentException if minimal requirements aren't met
     */
    static void checkResource(CkanResource resource) {
        checkNotNull(resource, "Can't create null resource!");
        checkNotEmpty(resource.getFormat(), "Invalid Ckan resource format!");
        checkNotEmpty(resource.getName(), "Ckan resource name can't be empty!");
        checkNotEmpty(resource.getDescription(), "Ckan resource description must not be null!");
        // todo do we need to check mimetype?? checkNotNull(resource.getMimetype());
        checkNotEmpty(resource.getPackageId(), "Ckan resource parent dataset must not be empty!");
        checkNotEmpty(resource.getUrl(), "Ckan resource url must be not empty!");
    }

    /**
     * Updates a resource on the ckan server.
     *
     * @param resource ckan resource object. Fields set to null won't be updated
     * on the server. WARNING: if you didn't set any additional custom field
     * with {@link CkanResource#putOthers(java.lang.String, java.lang.Object)},
     * existing custom fields on the server WILL NOT be changed. This behaviour
     * is different from CKAN inconsistent default one, which would always erase
     * custom fields on the server. For this reason provided {@code resource}
     * might be patched with latest metadata from the server prior sending it
     * for update. TODO review new patch api in CKAN 2.3
     * @return the updated resource
     * @throws JackanException
     */
    public synchronized CkanResource updateResource(CkanResourceBase resource) {

        if (ckanToken == null) {
            throw new JackanException("Tried to update resource" + resource.getName() + ", but ckan token was not set!");
        }

        if (resource.getOthers() == null) {
            LOG.info("Found no custom metadata on the resource data to send for update, "
                    + "merging custom metadata from the server into provided resource data "
                    + "to prevent accidental erasures...");
            CkanResource origRes = getResource(resource.getId());
            if (origRes.getOthers() != null) {
                for (Map.Entry<String, Object> entry : origRes.getOthers().entrySet()) {
                    resource.putOthers(entry.getKey(), entry.getValue());
                }
            }
        } else {
            LOG.info("Found custom metadata on the resource data to send for update, "
                    + "going to completely replace custom resource metadata on the server.");
        }

        String json = null;
        try {
            json = getObjectMapperForPosting().writeValueAsString(resource);
        }
        catch (IOException ex) {
            throw new JackanException("Couldn't jsonize the provided CkanResource!", ex);

        }

        return postHttp(ResourceResponse.class, "/api/3/action/resource_update", json, ContentType.APPLICATION_JSON).result;

    }

    /**
     * Updates a dataset on the ckan server.
     *
     * @param dataset ckan dataset object. Fields set to null won't be updated
     * on the server. WARNING: if you didn't set any additional custom field
     * with {@link CkanDataset#putOthers(java.lang.String, java.lang.Object)} or
     * any {@code extras} with {@link CkanDataset#setExtras(java.util.List) },
     * existing custom fields on the server WILL NOT be changed. This behaviour
     * is different from CKAN inconsistent default one, which would always erase
     * custom fields and {@code extras} on the server. For this reason provided
     * {@code dataset} might be patched with latest metadata from the server
     * prior sending it for update. TODO review new patch api in CKAN 2.3
     * @return the updated resource
     * @throws JackanException
     */
    public synchronized CkanDataset updateDataset(CkanDatasetBase dataset) {

        if (ckanToken == null) {
            throw new JackanException("Tried to update dataset" + dataset.getName() + ", but ckan token was not set!");
        }
        @Nullable
        CkanDataset origDataset = getDataset(dataset.getId());
        if (dataset.getOthers() == null) {
            LOG.info("Found no custom metadata (that is, anything other than the 'extras') on the dataset to send for update, "
                    + "merging custom metadata from the server into provided dataset "
                    + "to prevent accidental erasures...");
            origDataset = getDataset(dataset.getId());
            if (origDataset.getOthers() != null) {
                dataset.setOthers(origDataset.getOthers());
            }
        } else {
            LOG.info("Found custom metadata (that is, anything other than the 'extras') on the dataset to send for update, "
                    + "going to completely replace custom dataset metadata on the server.");
        }

        if (dataset.getExtras() == null) {
            LOG.info("Found no 'extras' field on the dataset to send for update, "
                    + "merging 'extras' from the server into provided dataset "
                    + "to prevent accidental erasures...");
            if (origDataset == null) {
                origDataset = getDataset(dataset.getId());
            }
            if (origDataset.getExtras() != null) {
                dataset.setExtras(origDataset.getExtras());
            }
        } else {
            LOG.info("Found 'extras' field on the dataset to send for update, "
                    + "going to completely replace 'extras' on the server.");
        }

        if (dataset.getResources() == null) {
            LOG.info("Found no 'resources' field on the dataset to send for update, "
                    + "merging 'resources' from the server into provided dataset "
                    + "to prevent accidental erasures...");
            if (origDataset == null) {
                origDataset = getDataset(dataset.getId());
            }
            if (origDataset.getResources() != null) {
                dataset.setResources(origDataset.getResources());
            }
        } else {
            LOG.info("Found 'resources' field on the dataset to send for update, "
                    + "going to completely replace 'resources' on the server.");
        }

        String json = null;
        try {
            json = getObjectMapperForPosting().writeValueAsString(dataset);
        }
        catch (IOException ex) {
            throw new JackanException("Couldn't jsonize the provided CkanResource!", ex);

        }

        return postHttp(DatasetResponse.class, "/api/3/action/package_update", json, ContentType.APPLICATION_JSON).result;

    }

    /**
     * Creates CkanDataset on the server. Will also create eventual resources
     * present in the dataset.
     *
     * @param dataset Ckan dataset without id
     * @return the newly created dataset
     * @throws JackanException
     */
    public synchronized CkanDataset createDataset(CkanDataset dataset) {

        if (ckanToken == null) {
            throw new JackanException("Tried to create dataset" + dataset.getName() + ", but ckan token was not set!");
        }

        String json = null;
        try {
            json = getObjectMapperForPosting().writeValueAsString(dataset);
        }
        catch (IOException e) {
            throw new JackanException("Couldn't serialize the provided CkanDataset!", this, e);

        }
        DatasetResponse response = postHttp(DatasetResponse.class, "/api/3/action/package_create", json, ContentType.APPLICATION_JSON);
        return response.result;
    }

    /**
     * Creates CkanOrganization on the server
     *
     * @param org organization to create
     * @return the newly created organization
     * @throws JackanException
     */
    public synchronized CkanOrganization createOrganization(CkanOrganization org) {

        if (ckanToken == null) {
            throw new JackanException("Tried to create organization" + org.getName() + ", but ckan token was not set!");
        }

        String json = null;
        try {
            json = getObjectMapperForPosting().writeValueAsString(org);
        }
        catch (IOException e) {
            throw new JackanException("Couldn't serialize the provided CkanOrganization!", this, e);

        }
        OrganizationResponse response = postHttp(OrganizationResponse.class, "/api/3/action/organization_create", json, ContentType.APPLICATION_JSON);
        return response.result;
    }

    /**
     * Returns the proxy used by the client.
     */
    @Nullable
    public HttpHost getProxy() {
        return proxy;

    }

}

class CkanResponse {

    public String help;
    public boolean success;
    public CkanError error;

}

class DatasetResponse extends CkanResponse {

    public CkanDataset result;
}

class ResourceResponse extends CkanResponse {

    public CkanResource result;
}

class DatasetListResponse extends CkanResponse {

    public List<String> result;
}

class UserListResponse extends CkanResponse {

    public List<CkanUser> result;
}

class UserResponse extends CkanResponse {

    public CkanUser result;
}

class TagListResponse extends CkanResponse {

    public List<CkanTag> result;
}

class OrganizationResponse extends CkanResponse {

    public CkanOrganization result;
}

class GroupResponse extends CkanResponse {

    public CkanGroup result;
}

class OrganizationListResponse extends CkanResponse {

    public List<CkanOrganization> result;
}

class GroupListResponse extends CkanResponse {

    public List<CkanGroup> result;
}

class GroupNamesResponse extends CkanResponse {

    public List<String> result;
}

class TagNamesResponse extends CkanResponse {

    public List<String> result;
}

class DatasetSearchResponse extends CkanResponse {

    public SearchResults<CkanDataset> result;
}

class LicenseListResponse extends CkanResponse {

    public List<CkanLicense> result;
}

class FormatListResponse extends CkanResponse {

    public Set<String> result;
}

class ApiVersionResponse {

    public int version;
}
