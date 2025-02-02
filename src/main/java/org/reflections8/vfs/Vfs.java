package org.reflections8.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarFile;

import org.reflections8.Reflections;
import org.reflections8.ReflectionsException;
import org.reflections8.util.ClasspathHelper;
import org.reflections8.util.Utils;

/**
 * a simple virtual file system bridge
 * <p>use the {@link org.reflections8.vfs.Vfs#fromURL(java.net.URL)} to get a {@link org.reflections8.vfs.Vfs.Dir},
 * then use {@link org.reflections8.vfs.Vfs.Dir#getFiles()} to iterate over the {@link org.reflections8.vfs.Vfs.File}
 * <p>for example:
 * <pre>
 *      Vfs.Dir dir = Vfs.fromURL(url);
 *      Iterable&lt;Vfs.File&gt; files = dir.getFiles();
 *      for (Vfs.File file : files) {
 *          InputStream is = file.openInputStream();
 *      }
 * </pre>
 * <p>{@link org.reflections8.vfs.Vfs#fromURL(java.net.URL)} uses static {@link org.reflections8.vfs.Vfs.DefaultUrlTypes} to resolve URLs.
 * It contains VfsTypes for handling for common resources such as local jar file, local directory, jar url, jar input stream and more.
 * <p>It can be plugged in with other {@link org.reflections8.vfs.Vfs.UrlType} using {@link org.reflections8.vfs.Vfs#addDefaultURLTypes(org.reflections8.vfs.Vfs.UrlType)} or {@link org.reflections8.vfs.Vfs#setDefaultURLTypes(java.util.List)}.
 * <p>for example:
 * <pre>
 *      Vfs.addDefaultURLTypes(new Vfs.UrlType() {
 *          public boolean matches(URL url)         {
 *              return url.getProtocol().equals("http");
 *          }
 *          public Vfs.Dir createDir(final URL url) {
 *              return new HttpDir(url); //implement this type... (check out a naive implementation on VfsTest)
 *          }
 *      });
 *
 *      Vfs.Dir dir = Vfs.fromURL(new URL("http://mirrors.ibiblio.org/pub/mirrors/maven2/org/slf4j/slf4j-api/1.5.6/slf4j-api-1.5.6.jar"));
 * </pre>
 * <p>use {@link org.reflections8.vfs.Vfs#findFiles(java.util.Collection, java.util.function.Predicate)} to get an
 * iteration of files matching given name predicate over given list of urls
 */
public abstract class Vfs {

    private static List<UrlType> defaultUrlTypes = new ArrayList<>();
    static {
      defaultUrlTypes.addAll(Arrays.asList(DefaultUrlTypes.values()));
    }

    /** an abstract vfs dir */
    public interface Dir {
        String getPath();
        Iterable<File> getFiles();
        void close();
    }

    /** an abstract vfs file */
    public interface File {
        String getName();
        String getRelativePath();
        InputStream openInputStream() throws IOException;
    }

    /** a matcher and factory for a url */
    public interface UrlType {
        boolean matches(URL url) throws Exception;
        Dir createDir(URL url) throws Exception;
    }

    /** the default url types that will be used when issuing {@link org.reflections8.vfs.Vfs#fromURL(java.net.URL)} */
    public static List<UrlType> getDefaultUrlTypes() {
        return defaultUrlTypes;
    }

    /** sets the static default url types. can be used to statically plug in urlTypes */
    public static void setDefaultURLTypes(final List<UrlType> urlTypes) {
        defaultUrlTypes = urlTypes;
    }

    /** add a static default url types to the beginning of the default url types list. can be used to statically plug in urlTypes */
    public static void addDefaultURLTypes(UrlType urlType) {
        defaultUrlTypes.add(0, urlType);
    }

    /** tries to create a Dir from the given url, using the defaultUrlTypes */
    public static Dir fromURL(final URL url) {
        return fromURL(url, defaultUrlTypes);
    }

    /** tries to create a Dir from the given url, using the given urlTypes*/
    public static Dir fromURL(final URL url, final List<UrlType> urlTypes) {
        for (UrlType type : urlTypes) {
            try {
                if (type.matches(url)) {
                    Dir dir = type.createDir(url);
                    if (dir != null) return dir;
                }
            } catch (Throwable e) {
                if (Reflections.log.isPresent()) {
                    Reflections.log.get().warn("could not create Dir using " + type + " from url " + url.toExternalForm() + ". skipping.", e);
                }
            }
        }

        throw new ReflectionsException("could not create Vfs.Dir from url, no matching UrlType was found [" + url.toExternalForm() + "]\n" +
                "either use fromURL(final URL url, final List<UrlType> urlTypes) or " +
                "use the static setDefaultURLTypes(final List<UrlType> urlTypes) or addDefaultURLTypes(UrlType urlType) " +
                "with your specialized UrlType.");
    }

    /** tries to create a Dir from the given url, using the given urlTypes*/
    public static Dir fromURL(final URL url, final UrlType... urlTypes) {
        return fromURL(url, Arrays.asList(urlTypes));
    }

    /** return an iterable of all {@link org.reflections8.vfs.Vfs.File} in given urls, starting with given packagePrefix and matching nameFilter */
    public static Iterable<File> findFiles(final Collection<URL> inUrls, final String packagePrefix, Predicate<String> nameFilter) {
        Predicate<File> fileNamePredicate = new Predicate<File>() {
            public boolean test(File file) {
                String path = file.getRelativePath();
                if (path.startsWith(packagePrefix)) {
                    String filename = path.substring(path.indexOf(packagePrefix) + packagePrefix.length());
                    return !Utils.isEmpty(filename) && nameFilter.test(filename.substring(1));
                } else {
                    return false;
                }
            }
        };

        return findFiles(inUrls, fileNamePredicate);
    }

    /** return an iterable of all {@link org.reflections8.vfs.Vfs.File} in given urls, matching filePredicate */
    public static Iterable<File> findFiles(final Collection<URL> inUrls, final Predicate<File> filePredicate) {
        final Iterable<File> result = new ArrayList<File>();

        for (final URL url : inUrls) {
            try {
                fromURL(url).getFiles().forEach(new Consumer<File>() {
                    @Override
                    public void accept(File file) {
                        if (filePredicate.test(file))
                            ((ArrayList<File>) result).add(file);
                    }
                });
            } catch (Throwable e) {
                if (Reflections.log.isPresent()) {
                    Reflections.log.get().error("could not findFiles for url. continuing. [" + url + "]", e);
                }
            }
        }

        return result;
    }

    /**try to get {@link java.io.File} from url*/
    public static Optional<java.io.File> getFile(URL url) {
        java.io.File file;
        String path;

        try {
            path = url.toURI().getSchemeSpecificPart();
            if ((file = new java.io.File(path)).exists()) return Optional.of(file);
        } catch (URISyntaxException e) {
        }

        try {
            path = URLDecoder.decode(url.getPath(), "UTF-8");
            if (path.contains(".jar!")) path = path.substring(0, path.lastIndexOf(".jar!") + ".jar".length());
            if ((file = new java.io.File(path)).exists()) return Optional.of(file);

        } catch (UnsupportedEncodingException e) {
        }

        try {
            path = url.toExternalForm();
            if (path.startsWith("jar:")) path = path.substring("jar:".length());
            if (path.startsWith("wsjar:")) path = path.substring("wsjar:".length());
            if (path.startsWith("file:")) path = path.substring("file:".length());
            if (path.contains(".jar!")) path = path.substring(0, path.indexOf(".jar!") + ".jar".length());
            if (path.contains(".war!")) path = path.substring(0, path.indexOf(".war!") + ".war".length());
            if ((file = new java.io.File(path)).exists()) return Optional.of(file);

            path = path.replace("%20", " ");
            if ((file = new java.io.File(path)).exists()) return Optional.of(file);

        } catch (Exception e) {
        }

        return Optional.empty();
    }
    
    private static boolean hasJarFileInPath(URL url) {
		return url.toExternalForm().matches(".*\\.jar(\\!.*|$)");
	}

    /** default url types used by {@link org.reflections8.vfs.Vfs#fromURL(java.net.URL)}
     * <p>
     * <p>jarFile - creates a {@link org.reflections8.vfs.ZipDir} over jar file
     * <p>jarUrl - creates a {@link org.reflections8.vfs.ZipDir} over a jar url (contains ".jar!/" in it's name), using Java's {@link JarURLConnection}
     * <p>directory - creates a {@link org.reflections8.vfs.SystemDir} over a file system directory
     * <p>jboss vfs - for protocols vfs, using jboss vfs (should be provided in classpath)
     * <p>jboss vfsfile - creates a {@link UrlTypeVFS} for protocols vfszip and vfsfile.
     * <p>bundle - for bundle protocol, using eclipse FileLocator (should be provided in classpath)
     * <p>jarInputStream - creates a {@link JarInputDir} over jar files, using Java's JarInputStream
     * */
    public static enum DefaultUrlTypes implements UrlType {
        jarFile {
            public boolean matches(URL url) {
                return url.getProtocol().equals("file") && hasJarFileInPath(url);
            }

            public Dir createDir(final URL url) throws Exception {
                return new ZipDir(new JarFile(getFile(url).get()));
            }
        },

        jarUrl {
            public boolean matches(URL url) {
                return "jar".equals(url.getProtocol()) || "zip".equals(url.getProtocol()) || "wsjar".equals(url.getProtocol());
            }

            public Dir createDir(URL url) throws Exception {
                try {
                    URLConnection urlConnection = url.openConnection();
                    if (urlConnection instanceof JarURLConnection) {
                        urlConnection.setUseCaches(false);
                        return new ZipDir(((JarURLConnection) urlConnection).getJarFile());
                    }
                } catch (Throwable e) { /*fallback*/ }
                Optional<java.io.File> file = getFile(url);
                if (file.isPresent()) {
                    return new ZipDir(new JarFile(file.get()));
                }
                return null;
            }
        },

        directory {
            public boolean matches(URL url) {
                if (url.getProtocol().equals("file") && !hasJarFileInPath(url)) {
                    Optional<java.io.File> file = getFile(url);
                    return file.isPresent() && file.get().isDirectory();
                } else return false;
            }

            public Dir createDir(final URL url) throws Exception {
                final Optional<java.io.File> file = getFile(url);
                return new SystemDir(file.isPresent() ? file.get() : null);
            }
        },

        jboss_vfs {
            public boolean matches(URL url) {
                return url.getProtocol().equals("vfs");
            }

            public Vfs.Dir createDir(URL url) throws Exception {
                Object content = url.openConnection().getContent();
                Class<?> virtualFile = ClasspathHelper.contextClassLoader().loadClass("org.jboss.vfs.VirtualFile");
                java.io.File physicalFile = (java.io.File) virtualFile.getMethod("getPhysicalFile").invoke(content);
                String name = (String) virtualFile.getMethod("getName").invoke(content);
                java.io.File file = new java.io.File(physicalFile.getParentFile(), name);
                if (!file.exists() || !file.canRead()) file = physicalFile;
                return file.isDirectory() ? new SystemDir(file) : new ZipDir(new JarFile(file));
            }
        },

        jboss_vfsfile {
            public boolean matches(URL url) throws Exception {
                return "vfszip".equals(url.getProtocol()) || "vfsfile".equals(url.getProtocol());
            }

            public Dir createDir(URL url) throws Exception {
                return new UrlTypeVFS().createDir(url);
            }
        },

        bundle {
            public boolean matches(URL url) throws Exception {
                return url.getProtocol().startsWith("bundle");
            }

            public Dir createDir(URL url) throws Exception {
                return fromURL((URL) ClasspathHelper.contextClassLoader().
                        loadClass("org.eclipse.core.runtime.FileLocator").getMethod("resolve", URL.class).invoke(null, url));
            }
        },

        jarInputStream {
            public boolean matches(URL url) throws Exception {
                return url.toExternalForm().contains(".jar");
            }

            public Dir createDir(final URL url) throws Exception {
                return new JarInputDir(url);
            }
        }
    }
}
