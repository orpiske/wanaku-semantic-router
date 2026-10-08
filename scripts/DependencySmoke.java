import ai.wanaku.capabilities.sdk.maven.GAV;
import ai.wanaku.capabilities.sdk.maven.WanakuMavenDownloader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks that the packaged SDK resolves dependencies as the container runtime user. */
class DependencySmoke {
    public static void main(String[] args) throws Exception {
        try (var downloader = new WanakuMavenDownloader(List.of(), Path.of(args[0]))) {
            var artifacts = downloader.download(List.of(GAV.parse("org.slf4j:slf4j-api:2.0.20")));
            if (artifacts.isEmpty() || artifacts.stream().anyMatch(path -> !Files.isRegularFile(path))) {
                throw new IllegalStateException("SDK did not resolve the runtime dependency");
            }
            if (downloader.getClassLoader().getResource("org/slf4j/Logger.class") == null) {
                throw new IllegalStateException("SDK did not expose the resolved dependency");
            }
        }
    }
}
