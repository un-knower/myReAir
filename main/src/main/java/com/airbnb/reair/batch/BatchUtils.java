package com.airbnb.reair.batch;

import com.airbnb.reair.common.FsUtils;
import com.airbnb.reair.incremental.deploy.ConfigurationKeys;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Progressable;

import java.io.IOException;

/**
 * Utilities for batch replication.
 */
public class BatchUtils {
  private static final Log LOG = LogFactory.getLog(BatchUtils.class);

  /**
   * Executes a file copy.
   *
   * @param conf Hadoop configuration object
   * @param srcFileStatus Status of the source file
   * @param srcFs Source FileSystem
   * @param dstDir Destination directory
   * @param dstFs Destination FileSystem
   * @param tmpDirPath Temporary copy staging location.
   * @param progressable A progressable object to progress during long file copies
   * @param forceUpdate Whether to force a copy
   * @param identifier Identifier to use in the temporary file
   * @return An error string or null if successful
   */
  public static String doCopyFileAction(
      Configuration conf,
      SimpleFileStatus srcFileStatus,
      FileSystem srcFs,
      String dstDir,
      FileSystem dstFs,
      Path tmpDirPath,
      Progressable progressable,
      boolean forceUpdate,
      String identifier) {
    // TODO: Should be configurable
    int retry = 3;
    String lastError = null;

    while (retry > 0) {
      try {
        Path srcPath = new Path(srcFileStatus.getFullPath());
        if (!srcFs.exists(srcPath)) {
          LOG.info("Src does not exist. " + srcFileStatus.getFullPath());
          return "Src does not exist. " + srcFileStatus.getFullPath();
        }
        FileStatus srcStatus = srcFs.getFileStatus(srcPath);

        Path dstPath = new Path(dstDir, srcFileStatus.getFileName());
        // if dst already exists.
        if (dstFs.exists(dstPath)) {
          FileStatus dstStatus = dstFs.getFileStatus(dstPath);
          // If it is not force update, and the file size are same we will not recopy.
          // This normally happens when we do retry run.
          if (!forceUpdate && srcStatus.getLen() == dstStatus.getLen()) {//如果不强制刷新，src/desc文件大小相同 skip
            LOG.info("dst already exists. " + dstPath.toString());
            return "dst already exists. " + dstPath.toString();
          }
        }

        Path dstParentPath = new Path(dstDir);
        if (!dstFs.exists(dstParentPath) && !dstFs.mkdirs(dstParentPath)) { //如果dest路径不存在，并且创建路径失败
          LOG.info("Could not create directory: " + dstDir);
          return "Could not create directory: " + dstDir;
        }

        Path tmpDstPath = new Path(tmpDirPath,
            "__tmp__copy__file_" + identifier + "_" + srcFileStatus.getFileName()
                + "." + System.currentTimeMillis());
        if (dstFs.exists(tmpDstPath)) {
          dstFs.delete(tmpDstPath, false);
        }

        // Keep the same replication factor and block size as the source file.
        try (FSDataInputStream inputStream = srcFs.open(srcPath);
          FSDataOutputStream outputStream = dstFs.create(
            tmpDstPath,
            srcStatus.getPermission(),
            true,
            conf.getInt("io.file.buffer.size", 4096),
            srcStatus.getReplication(),
            srcStatus.getBlockSize(),
            progressable)) {
          IOUtils.copyBytes(inputStream, outputStream, conf);//把srcFile拷贝到destCluster tmpDestPath
        }

        if (forceUpdate && dstFs.exists(dstPath)) {//如果强制刷新复制，并且destPath存在就做删除处理
          dstFs.delete(dstPath, false);
        }

        // If checksums exist and don't match, re-do the copy. If checksums do not exist, assume
        // that they match.
        if (conf.getBoolean(ConfigurationKeys.BATCH_JOB_VERIFY_COPY_CHECKSUM, true) //校验srcFile与copy tmpDstPath文件的checksum是否相同
            && !FsUtils.checksumsMatch(conf, srcPath, tmpDstPath).map(Boolean::booleanValue).orElse(true)) {
          throw new IOException(String.format("Not renaming %s to %s since checksums do not match "
                  + "between %s and %s",
              tmpDstPath,
              dstPath,
              srcPath,
              tmpDstPath));
        }

        dstFs.rename(tmpDstPath, dstPath);// 如果匹配　rename操作
        dstFs.setTimes(dstPath, srcStatus.getModificationTime(), srcStatus.getAccessTime()); //并且把文件的修改时间,访问时间修改为与src一致
        LOG.info(dstPath.toString() + " file copied");
        progressable.progress();
        return null;
      } catch (IOException e) {
        LOG.info("Got an exception!", e);
        lastError = e.getMessage();
        --retry;
      }
    }

    return lastError;
  }
}
