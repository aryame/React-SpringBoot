package app.service;

import app.aop.MethodTime;
import app.constant.MovieTypeEnum;
import app.entity.Film;
import app.service.db.DataService;
import app.vo.movie.Movie;
import app.vo.movie.MovieSubject;
import app.vo.movie.MovieVo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static app.util.ConstantUtils.SEPARATOR;

/**
 * @author zhihao zhang
 * @since 2017.10.18
 */

@Service
@Slf4j
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class MovieService {
    private static final String DOUBAN_URL = "https://douban.uieee.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<MovieTypeEnum, String> URL_MAP = ImmutableMap.of(
            MovieTypeEnum.RECENT, DOUBAN_URL.concat("/v2/movie/in_theaters?city=上海"),
            MovieTypeEnum.TOP, DOUBAN_URL.concat("/v2/movie/top250?start=0&count=100")
    );

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();
    private static final ExecutorService executorService =
            new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>());

    private DataService dataService;

    public List<Film> getMoviesByMovieTypeEnum(MovieTypeEnum movieTypeEnum) {
        return dataService.findByMovieTypeEnum(movieTypeEnum);
    }

    public List<Film> getAllMovies() {
        return dataService.listAllFilms();
    }

    public Film getMovieById(Long id) {
        return dataService.findByMovieId(id);
    }

    public List<Film> getMoviesByMovieIds(List<Long> movieIdList) {
        List<Long> filterMovieIdList = movieIdList.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        List<Long> existedIdList = dataService.findByMovieIds(filterMovieIdList).stream()
                .filter(film -> Objects.nonNull(film.getSummary()))
                .map(Film::getMovieId)
                .collect(Collectors.toList());
        List<Film> newFilmList = filterMovieIdList.stream()
                .filter(movieId -> !existedIdList.contains(movieId))
                .map(this::syncMovieByMovieId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        this.batchUpdateFilmList(newFilmList);
        return dataService.findByMovieIds(filterMovieIdList);
    }

    public Film syncMovieByMovieId(Long movieId) {
        Film film = dataService.findByMovieId(movieId);
        MovieSubject movieSubject;
        try {
            movieSubject = getMovieSubject(movieId);
        } catch (Exception e) {
            log.error("can not fetch this movieId: {}", movieId);
            return null;
        }

        if (Objects.isNull(movieSubject)) {
            return null;
        }
        Film syncedFilm = Film.transformMovieSubjectToFilm(movieSubject, MovieTypeEnum.NORMAL);

        if (Objects.nonNull(film)) {
            syncedFilm.setId(film.getId());
        }

        return syncedFilm;
    }

    public MovieSubject getMovieSubject(Long id) {
        String url = DOUBAN_URL.concat("/v2/movie/subject/").concat(String.valueOf(id));
        try {
            return MAPPER.readValue(getUrlContent(url),
                    TypeFactory.defaultInstance().constructType(MovieSubject.class));
        } catch (Exception e) {
            return null;
        }
    }

    @MethodTime
    public void syncMovies(MovieTypeEnum movieTypeEnum) {
        this.saveMovie(movieTypeEnum);
        this.saveDetailToMovie(movieTypeEnum);
    }

    private void saveMovie(MovieTypeEnum movieTypeEnum) {
        List<Movie> movieList = getMoviesFromOrigin(URL_MAP.get(movieTypeEnum));
        if (CollectionUtils.isEmpty(movieList)) {
            return;
        }
        this.deleteOutDatedMovie(movieTypeEnum);
        this.saveFilmList(movieList, movieTypeEnum);
    }

    private List<Movie> getMoviesFromOrigin(String url) {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try {
            MovieVo movieVo = MAPPER.readValue(getUrlContent(url),
                    TypeFactory.defaultInstance().constructType(MovieVo.class));
            return movieVo.getSubjects();
        } catch (Exception e) {
            log.error("failed to get recent movie info: ", e);
            return Lists.newArrayList();
        }
    }

    private String getUrlContent(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        Response response = HTTP_CLIENT.newCall(request).execute();
        assert response.body() != null;
        return response.body().string();
    }

    private void deleteOutDatedMovie(MovieTypeEnum movieTypeEnum) {
        List<Film> filmList = dataService.findByMovieTypeEnum(movieTypeEnum);
        filmList.forEach(film -> film.setMovieTypeEnum(MovieTypeEnum.NORMAL));
        dataService.saveAll(filmList);
        log.info("set old recent {} movies to normal movies", filmList.size());
    }

    private void saveFilmList(List<Movie> movieList, MovieTypeEnum movieTypeEnum) {
        List<Film> filmList = movieList.stream()
                .map(movie -> Film.transformMovieAndOldFilmToNewFilm(
                        movie, movieTypeEnum, dataService.findByMovieId(movie.getId())))
                .collect(Collectors.toList());
        this.batchUpdateFilmList(filmList);
    }

    private void batchUpdateFilmList(List<Film> filmList) {
        if (CollectionUtils.isEmpty(filmList)) {
            return;
        }
        dataService.saveAll(filmList);
        log.info("update {} movie items summary and country", filmList.size());
    }

    private void saveDetailToMovie(MovieTypeEnum movieTypeEnum) {
        List<Film> filmList = dataService.findByMovieTypeEnum(movieTypeEnum);
        List<Film> newFilmList = Lists.newArrayList();
        List<CompletableFuture<Boolean>> completableFuture =
                filmList.stream()
                        .filter(film -> Strings.isNullOrEmpty(film.getSummary()))
                        .map(film -> CompletableFuture.supplyAsync(() -> getMovieSubject(film.getMovieId()),
                                executorService)
                                .thenApply(movieSubject -> {
                                    if (Objects.nonNull(movieSubject)) {
                                        film.setSummary(movieSubject.getSummary());
                                        film.setCountries(StringUtils.join(movieSubject.getCountries().toArray(), SEPARATOR));
                                        newFilmList.add(film);
                                        return true;
                                    }
                                    return false;
                                }))
                        .collect(Collectors.toList());

        for (Future<Boolean> future : completableFuture) {
            try {
                boolean fetchStatus = future.get();
                log.warn("update summary success: {}", fetchStatus);
            } catch (Exception e) {
                log.error("get movie summary error", e);
            }
        }

        this.batchUpdateFilmList(newFilmList);
    }
}