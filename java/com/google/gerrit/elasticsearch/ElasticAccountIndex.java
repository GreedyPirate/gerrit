// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.elasticsearch;

import static com.google.gerrit.server.index.account.AccountField.ID;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.elasticsearch.ElasticMapping.MappingProperties;
import com.google.gerrit.index.QueryOptions;
import com.google.gerrit.index.Schema;
import com.google.gerrit.index.query.DataSource;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.index.IndexUtils;
import com.google.gerrit.server.index.account.AccountField;
import com.google.gerrit.server.index.account.AccountIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.Set;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.eclipse.jgit.lib.Config;
import org.elasticsearch.client.Response;

public class ElasticAccountIndex extends AbstractElasticIndex<Account.Id, AccountState>
    implements AccountIndex {
  static class AccountMapping {
    MappingProperties accounts;

    AccountMapping(Schema<AccountState> schema) {
      this.accounts = ElasticMapping.createMapping(schema);
    }
  }

  static final String ACCOUNTS = "accounts";

  private final AccountMapping mapping;
  private final Provider<AccountCache> accountCache;

  @Inject
  ElasticAccountIndex(
      @GerritServerConfig Config cfg,
      SitePaths sitePaths,
      Provider<AccountCache> accountCache,
      ElasticRestClientBuilder clientBuilder,
      @Assisted Schema<AccountState> schema) {
    super(cfg, sitePaths, schema, clientBuilder, ACCOUNTS);
    this.accountCache = accountCache;
    this.mapping = new AccountMapping(schema);
  }

  @Override
  public void replace(AccountState as) throws IOException {
    String bulk = toAction(ACCOUNTS, getId(as), INDEX);
    bulk += toDoc(as);

    String uri = getURI(ACCOUNTS, BULK);
    Response response = performRequest(HttpPost.METHOD_NAME, bulk, uri, getRefreshParam());
    int statusCode = response.getStatusLine().getStatusCode();
    if (statusCode != HttpStatus.SC_OK) {
      throw new IOException(
          String.format(
              "Failed to replace account %s in index %s: %s",
              as.getAccount().getId(), indexName, statusCode));
    }
  }

  @Override
  public DataSource<AccountState> getSource(Predicate<AccountState> p, QueryOptions opts)
      throws QueryParseException {
    JsonArray sortArray = getSortArray(AccountField.ID.getName());
    return new ElasticQuerySource(
        p, opts.filterFields(IndexUtils::accountFields), ACCOUNTS, sortArray);
  }

  @Override
  protected String addActions(Account.Id c) {
    return delete(ACCOUNTS, c);
  }

  @Override
  protected String getMappings() {
    ImmutableMap<String, AccountMapping> mappings = ImmutableMap.of("mappings", mapping);
    return gson.toJson(mappings);
  }

  @Override
  protected String getId(AccountState as) {
    return as.getAccount().getId().toString();
  }

  @Override
  protected AccountState fromDocument(JsonObject json, Set<String> fields) {
    JsonElement source = json.get("_source");
    if (source == null) {
      source = json.getAsJsonObject().get("fields");
    }

    Account.Id id = new Account.Id(source.getAsJsonObject().get(ID.getName()).getAsInt());
    // Use the AccountCache rather than depending on any stored fields in the document (of which
    // there shouldn't be any). The most expensive part to compute anyway is the effective group
    // IDs, and we don't have a good way to reindex when those change.
    // If the account doesn't exist return an empty AccountState to represent the missing account
    // to account the fact that the account exists in the index.
    return accountCache.get().getEvenIfMissing(id);
  }
}
