/*
 * Copyright (c) 2018 - 2022 TomTom N.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tomtom.hopic

import com.tomtom.hopic.BasePullRequest

public class GithubPullRequest extends BasePullRequest {
  private String url
  private String credentialsId
  private String restUrl = null
  private String baseRestUrl = null
  private Map info = null
  private keyIds = [:]

  GithubPullRequest(steps, String url, String credentialsId, String refspec) {
    super(steps, refspec)
    this.url = url
    this.credentialsId = credentialsId

    if (this.url != null) {
      // transform e.g. https://github.com/tomtom-international/hopic/pull/42 to https://api.github.com/repos/tomtom-international/hopic/pulls/42
      this.restUrl = url.replaceFirst(/^([^:]+:[\/]*)(.+?)\/(.+?)\/(.+?)\/pull\/(\d+)$/, '$1api.$2/repos/$3/$4/pulls/$5')
      this.baseRestUrl = this.restUrl.replaceFirst(/\/repos\/.*$/, '')
    }
  }

  private def get_info(allow_cache = true) {
    if (allow_cache && this.info) {
      return this.info
    }
    if (url == null
     || !url.contains('/pull/')) {
     return null
    }
    def info = steps.readJSON(text: steps.httpRequest(
        url: restUrl,
        httpMode: 'GET',
        authentication: credentialsId,
      ).content)

    if (info.getOrDefault('user', [:]).containsKey('login')) {
      info['user'] = steps.readJSON(text: steps.httpRequest(
          url: "${this.baseRestUrl}/users/${info.user.login}",
          httpMode: 'GET',
          authentication: credentialsId,
        ).content)
    }

    def users = [:]
    if (info.getOrDefault('user', [:]).containsKey('name')) {
      users[info.user.login] = info.user
    }

    info['reviews'] = steps.readJSON(text: steps.httpRequest(
        url: "${this.restUrl}/reviews",
        httpMode: 'GET',
        authentication: credentialsId,
      ).content)
    info.reviews.each { review ->
      if (review.getOrDefault('user', [:]).containsKey('login')) {
        if (!users.containsKey(review.user.login)) {
          users[review.user.login] = steps.readJSON(text: steps.httpRequest(
              url: "${this.baseRestUrl}/users/${review.user.login}",
              httpMode: 'GET',
              authentication: credentialsId,
            ).content)
        }
        review['user'] = users[review.user.login]
      }
    }

    // Expand '@user' tokens in pull request description to 'Full Name <Full.Name@example.com>'
    // because we don't have this mapping handy when reading git commit messages.
    if (info.containsKey('body')) {
      def user_replacements = find_username_replacements(info.body)

      int last_idx = 0
      String new_description = ''
      user_replacements.each { repl ->
        def (username, start, end) = repl
        if (!users.containsKey(username)) {
          def response = steps.httpRequest(
              url: "${this.baseRestUrl}/users/${username}",
              httpMode: 'GET',
              authentication: credentialsId,
              validResponseCodes: '200,404',
            )
          def json = response.content ? steps.readJSON(text: response.content) : [:]
          if (response.status == 200) {
            users[username] = json
          } else {
            steps.println("\033[31m[error] could not find GitHub user '${username}'\033[39m")
          }
        }

        if (users.containsKey(username)) {
          def user = users[username]

          def str = user.getOrDefault('name', username)
          if (user.email && user.email != 'null') {
            str = "${str} <${user.email}>"
          }

          // Because Groovy is unable to obtain empty substrings
          if (last_idx != start)
            new_description = new_description + info.body[last_idx..start - 1]
          new_description = new_description + str
          last_idx = end
        }
      }

      // Because Groovy is unable to obtain an empty trailing string
      if (last_idx != info.body.length())
        new_description = new_description + info.body[last_idx..-1]
      info.body = new_description.replace('\r\n', '\n')
    }

    info['author_time'] = info.getOrDefault('updated_at', String.format("@%.3f", steps.currentBuild.timeInMillis / 1000.0))
    info['commit_time'] = String.format("@%.3f", steps.currentBuild.startTimeInMillis / 1000.0)
    this.info = info
    return info
  }

  @Override
  public boolean maySubmit(String target_commit, String source_commit, boolean allow_cache = true) {
    if (!super.maySubmitImpl(target_commit, source_commit, allow_cache)) {
      return false
    }

    // TODO: implement a PR merge policy configuration and evaluation framework and call that here
    steps.println("\033[36m[info] not submitting because the GitHub merge criteria are unknown\033[39m")
    steps.currentBuild.description = "Not submitting: GitHub merge criteria unknown"
    return false
  }

  @Override
  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    def change_request = this.get_info()
    def extra_params = ''
    if (change_request.containsKey('body')) {
      extra_params += ' --description=' + shell_quote(change_request.body)
    }

    // Record approving reviewers for auditing purposes
    def approvers = change_request.getOrDefault('reviews', []).findAll { review ->
        review.state == 'APPROVED'
      }.collect { review ->
        def str = review.user.name
        if (review.user.email && review.user.email != 'null') {
          str = "${str} <${review.user.email}>"
        }
        return str + ':' + review.commit_id
      }.sort()
    approvers.each { approver ->
      extra_params += ' --approved-by=' + shell_quote(approver)
    }

    if (this.source_commit == null) {
      // Pin to the head commit of the PR to ensure every node builds the same version, even when the PR gets updated while the build runs
      this.source_commit = this.current_source_commit(source_remote)
    }
    def cr_author = change_request.getOrDefault('user', [:])
    def author_name = cr_author.name
    if (!author_name || author_name == 'null')
      author_name = steps.env.CHANGE_AUTHOR
    if (!author_name || author_name == 'null')
      author_name = 'Unknown user'
    def author_email = cr_author.email
    if (!author_email || author_email == 'null')
      author_email = steps.env.CHANGE_AUTHOR_EMAIL
    if (!author_email || author_email == 'null')
      author_email = ''
    def merge_bundle = steps.pwd(tmp: true) + '/merge-transfer.bundle'
    def output = line_split(steps.sh(script: cmd
                                + ' prepare-source-tree'
                                + ' --author-name=' + shell_quote(author_name)
                                + ' --author-email=' + shell_quote(author_email)
                                + ' --author-date=' + shell_quote(change_request.author_time)
                                + ' --commit-date=' + shell_quote(change_request.commit_time)
                                + ' --bundle=' + shell_quote(merge_bundle)
                                + (pip_constraints_file ? (' --constraints=' + pip_constraints_file) : '')
                                + ' merge-change-request'
                                + ' --source-remote=' + shell_quote(source_remote)
                                + ' --source-ref=' + shell_quote(this.source_commit)
                                + ' --change-request=' + shell_quote(change_request.getOrDefault('number', steps.env.CHANGE_ID))
                                + ' --title=' + shell_quote(change_request.getOrDefault('title', steps.env.CHANGE_TITLE))
                                + extra_params,
                          label: 'Hopic: preparing source tree',
                          returnStdout: true)).findAll{it.size() > 0}
    if (output.size() <= 0) {
      return null
    }
    def rv = [
        commit: output.remove(0),
        bundle: merge_bundle,
      ]
    if (output.size() > 0) {
      rv.version = output.remove(0)
    }
    return rv
  }
}
