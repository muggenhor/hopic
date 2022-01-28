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

import com.tomtom.hopic.ChangeRequest

public class ModalityRequest extends ChangeRequest {
  private modality
  private info = null

  ModalityRequest(steps, modality) {
    super(steps)
    this.modality = modality
  }

  @Override
  public Map getinfo(String cmd) {
    if (this.info == null) {
      this.info = steps.readJSON(text: steps.sh(
        script: "${cmd} getinfo --modality=${shell_quote(modality)}",
        label: "Hopic: retrieving configuration for modality '${modality}'",
        returnStdout: true,
      ))
    }
    return this.info
  }

  @Override
  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    def author_time = steps.currentBuild.timeInMillis / 1000.0
    def commit_time = steps.currentBuild.startTimeInMillis / 1000.0
    def modality_bundle = steps.pwd(tmp: true) + '/modality-transfer.bundle'
    def prepare_cmd = (cmd
      + ' prepare-source-tree'
      + ' --author-date=' + shell_quote(String.format("@%.3f", author_time))
      + ' --commit-date=' + shell_quote(String.format("@%.3f", commit_time))
      + ' --bundle=' + shell_quote(modality_bundle)
      + (pip_constraints_file ? (' --constraints=' + pip_constraints_file) : '')
    )
    def full_cmd = "${prepare_cmd} apply-modality-change ${shell_quote(modality)}"
    if (modality == 'BUMP_VERSION') {
      full_cmd = "${prepare_cmd} bump-version"
    }
    def output = line_split(steps.sh(script: full_cmd,
                            label: 'Hopic: preparing modality change to ' + modality,
                            returnStdout: true)).findAll{it.size() > 0}
    if (output.size() <= 0) {
      return null
    }
    def rv = [
        commit: output.remove(0),
        bundle: modality_bundle,
      ]
    if (output.size() > 0) {
      rv.version = output.remove(0)
    }
    return rv
  }
}
