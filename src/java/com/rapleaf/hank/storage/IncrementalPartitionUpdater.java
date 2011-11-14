/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.rapleaf.hank.storage;

import com.rapleaf.hank.coordinator.Domain;
import com.rapleaf.hank.coordinator.DomainVersion;
import com.rapleaf.hank.coordinator.DomainVersions;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;

public abstract class IncrementalPartitionUpdater implements PartitionUpdater {

  private static final Logger LOG = Logger.getLogger(IncrementalPartitionUpdater.class);

  public static final String FETCH_ROOT_PREFIX = "_fetch_";
  public static final String UPDATE_WORK_ROOT_PREFIX = "_update_work_";
  public static final String CACHE_ROOT_NAME = "cache";

  protected final Domain domain;
  protected final String localPartitionRoot;
  protected final String localPartitionRootCache;

  public IncrementalPartitionUpdater(Domain domain,
                                     String localPartitionRoot) throws IOException {
    this.domain = domain;
    this.localPartitionRoot = localPartitionRoot;
    this.localPartitionRootCache = localPartitionRoot + "/" + CACHE_ROOT_NAME;
  }

  /**
   * @return The current valid version number or null if there is none
   * @throws IOException
   */
  protected abstract Integer detectCurrentVersionNumber() throws IOException;

  protected abstract DomainVersion getParentDomainVersion(DomainVersion domainVersion) throws IOException;

  protected abstract Set<DomainVersion> detectCachedBasesCore() throws IOException;

  protected abstract Set<DomainVersion> detectCachedDeltasCore() throws IOException;

  protected abstract void cleanCachedVersions() throws IOException;

  protected abstract void fetchVersion(DomainVersion version, String fetchRoot) throws IOException;

  protected abstract void runUpdateCore(DomainVersion currentVersion,
                                        DomainVersion updatingToVersion,
                                        IncrementalUpdatePlan updatePlan,
                                        String updateWorkRoot) throws IOException;

  @Override
  public void updateTo(DomainVersion updatingToVersion) throws IOException {
    ensureCacheExists();
    DomainVersion currentVersion = detectCurrentVersion();
    Set<DomainVersion> cachedBases = detectCachedBases();
    Set<DomainVersion> cachedDeltas = detectCachedDeltas();
    IncrementalUpdatePlan updatePlan = computeUpdatePlan(currentVersion, cachedBases, updatingToVersion);
    // The plan is empty, we are done
    if (updatePlan == null) {
      return;
    }
    try {
      // Fetch and cache versions needed to update
      cacheVersionsNeededToUpdate(currentVersion, cachedBases, cachedDeltas, updatePlan);
      // Run update in a workspace
      runUpdate(currentVersion, updatingToVersion, updatePlan);
    } finally {
      cleanCachedVersions();
    }
  }

  public Set<DomainVersion> detectCachedBases() throws IOException {
    ensureCacheExists();
    return detectCachedBasesCore();
  }

  public Set<DomainVersion> detectCachedDeltas() throws IOException {
    ensureCacheExists();
    return detectCachedDeltasCore();
  }

  // Fetch required versions and commit them to cache upon successful fetch
  protected void cacheVersionsNeededToUpdate(DomainVersion currentVersion,
                                             Set<DomainVersion> cachedBases,
                                             Set<DomainVersion> cachedDeltas,
                                             IncrementalUpdatePlan updatePlan) throws IOException {
    try {
      ensureCacheExists();
      // Clean all previous fetch roots
      deleteFetchRoots();
      // Create new fetch root
      File fetchRoot = createFetchRoot();
      // Fetch versions
      for (DomainVersion version : updatePlan.getAllVersions()) {
        // Do not fetch current version
        if (currentVersion != null && currentVersion.equals(version)) {
          continue;
        }
        // Do not fetch cached versions
        if (cachedBases.contains(version) || cachedDeltas.contains(version)) {
          continue;
        }
        fetchVersion(version, fetchRoot.getAbsolutePath());
      }
      // Commit fetched versions to cache
      commitFiles(fetchRoot, localPartitionRootCache);
    } finally {
      // Always delete fetch roots
      deleteFetchRoots();
    }
  }

  private void runUpdate(DomainVersion currentVersion,
                         DomainVersion updatingToVersion,
                         IncrementalUpdatePlan updatePlan) throws IOException {
    // Clean all previous update work roots
    deleteUpdateWorkRoots();
    // Create new update work root
    File updateWorkRoot = createUpdateWorkRoot();
    try {
      // Execute update
      runUpdateCore(currentVersion, updatingToVersion, updatePlan, updateWorkRoot.getAbsolutePath());
      // Move current version to cache
      commitFiles(new File(localPartitionRoot), localPartitionRootCache);
      // Commit update result files to top level
      commitFiles(updateWorkRoot, localPartitionRoot);
    } finally {
      deleteUpdateWorkRoots();
    }
  }

  // Move all files in sourceRoot to destinationRoot. Directories are ignored.
  protected void commitFiles(File sourceRoot, String destinationRoot) throws IOException {
    for (File file : sourceRoot.listFiles()) {
      // Skip non files
      if (!file.isFile()) {
        continue;
      }
      File targetFile = new File(destinationRoot + "/" + file.getName());
      // If target file already exists, delete it
      if (targetFile.exists()) {
        if (!targetFile.delete()) {
          throw new IOException("Failed to overwrite file in destination root: " + targetFile.getAbsolutePath());
        }
      }
      // Move file to destination
      if (!file.renameTo(targetFile)) {
        LOG.info("Committing " + file.getAbsolutePath() + " to " + targetFile.getAbsolutePath());
        throw new IOException("Failed to rename source file: " + file.getAbsolutePath()
            + " to destination file: " + targetFile.getAbsolutePath());
      }
    }
  }

  private void ensureCacheExists() throws IOException {
    // Create cache directory if it doesn't exist
    File cacheRootFile = new File(localPartitionRootCache);
    if (!cacheRootFile.exists()) {
      if (!cacheRootFile.mkdir()) {
        throw new IOException("Failed to create cache root directory: " + cacheRootFile.getAbsolutePath());
      }
    }
  }

  /**
   * Return the list of versions needed to update to the specific version given that
   * the specified current version and cached bases are available.
   * @param currentVersion
   * @param cachedBases
   * @param updatingToVersion
   * @return
   * @throws java.io.IOException
   */
  protected IncrementalUpdatePlan computeUpdatePlan(DomainVersion currentVersion,
                                                    Set<DomainVersion> cachedBases,
                                                    DomainVersion updatingToVersion) throws IOException {
    LinkedList<DomainVersion> updatePlanVersions = new LinkedList<DomainVersion>();
    // Backtrack versions (ignoring defunct versions) until we find:
    // - a base (no parent)
    // - or the current version (which is by definition a base or a rebased delta)
    // - or a version that is a base and that is cached
    DomainVersion parentVersion = updatingToVersion;
    while (parentVersion != null) {
      // Ignore completely defunct versions
      if (!parentVersion.isDefunct()) {
        // If a version along the path is still open, abort
        if (!DomainVersions.isClosed(parentVersion)) {
          throw new IOException("Detected a domain version that is still open"
              + " along the path from current version to version to update to:"
              + " open version: " + parentVersion
              + " current version: " + currentVersion
              + " updating to version: " + updatingToVersion);
        }
        // If backtrack to current version, use it and stop backtracking
        if (currentVersion != null && parentVersion.equals(currentVersion)) {
          // If we only need the current version, we don't need any plan
          if (updatePlanVersions.isEmpty()) {
            return null;
          } else {
            updatePlanVersions.add(parentVersion);
            break;
          }
        }
        // If backtrack to cached base version, use it and stop backtracking
        if (cachedBases.contains(parentVersion)) {
          updatePlanVersions.add(parentVersion);
          break;
        }
        // Add backtracked version to versions needed
        updatePlanVersions.add(parentVersion);
      }
      // Move to parent version
      parentVersion = getParentDomainVersion(parentVersion);
    }
    if (updatePlanVersions.isEmpty()) {
      return null;
    }
    // The base is the last version that was added (a base, the current version or a cached base)
    DomainVersion base = updatePlanVersions.removeLast();
    // Reverse list of deltas as we have added versions going backwards
    Collections.reverse(updatePlanVersions);
    return new IncrementalUpdatePlan(base, updatePlanVersions);
  }

  private DomainVersion detectCurrentVersion() throws IOException {
    Integer currentVersionNumber = detectCurrentVersionNumber();
    if (currentVersionNumber != null) {
      return domain.getVersionByNumber(currentVersionNumber);
    } else {
      return null;
    }
  }

  private File createUpdateWorkRoot() throws IOException {
    return createTmpWorkRoot(UPDATE_WORK_ROOT_PREFIX);
  }

  private void deleteUpdateWorkRoots() throws IOException {
    deleteTmpWorkRoots(UPDATE_WORK_ROOT_PREFIX);
  }

  private File createFetchRoot() throws IOException {
    return createTmpWorkRoot(FETCH_ROOT_PREFIX);
  }

  private void deleteFetchRoots() throws IOException {
    deleteTmpWorkRoots(FETCH_ROOT_PREFIX);
  }

  private File createTmpWorkRoot(String prefix) throws IOException {
    String tmpRoot = localPartitionRoot + "/" + prefix + UUID.randomUUID().toString();
    File tmpRootFile = new File(tmpRoot);
    if (!tmpRootFile.mkdir()) {
      throw new IOException("Failed to create temporary work root: " + tmpRoot);
    }
    return tmpRootFile;
  }

  private void deleteTmpWorkRoots(String prefix) throws IOException {
    for (File file : new File(localPartitionRoot).listFiles()) {
      if (file.isDirectory() && file.getName().startsWith(prefix)) {
        FileUtils.deleteDirectory(file);
      }
    }
  }
}
