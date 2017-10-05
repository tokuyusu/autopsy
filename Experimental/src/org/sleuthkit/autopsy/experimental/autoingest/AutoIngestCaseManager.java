/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
package org.sleuthkit.autopsy.experimental.autoingest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.CaseActionException;
import org.sleuthkit.autopsy.experimental.configuration.AutoIngestUserPreferences;

/**
 * Handles locating and opening cases created by auto ingest.
 */
final class AutoIngestCaseManager {

    private static AutoIngestCaseManager instance;

    /**
     * Gets the auto ingest case manager.
     *
     * @return The auto ingest case manager singleton.
     */
    synchronized static AutoIngestCaseManager getInstance() {
        if (null == instance) {
            instance = new AutoIngestCaseManager();
        }
        return instance;
    }

    /**
     * Constructs an object that handles locating and opening cases created by
     * auto ingest.
     */
    private AutoIngestCaseManager() {
    }

    /*
     * Gets a list of the cases in the top level case folder used by auto
     * ingest.
     */
    List<AutoIngestCase> getCases() {
        List<AutoIngestCase> cases = new ArrayList<>();
        List<Path> caseFolders = PathUtils.findCaseFolders(Paths.get(AutoIngestUserPreferences.getAutoModeResultsFolder()));
        for (Path caseFolderPath : caseFolders) {
            cases.add(new AutoIngestCase(caseFolderPath));
        }
        return cases;
    }

    /**
     * Opens an auto ingest case case.
     *
     * @param caseMetadataFilePath Path to the case metadata file.
     *
     * @throws CaseActionException
     */
    synchronized void openCase(Path caseMetadataFilePath) throws CaseActionException {
        /*
         * Open the case.
         */
        Case.openAsCurrentCase(caseMetadataFilePath.toString());
    }
}
