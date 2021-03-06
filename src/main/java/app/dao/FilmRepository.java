package app.dao;

import app.constant.MovieTypeEnum;
import app.entity.Film;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author zhihao zhang
 * @since 2019.06.10
 */

@Repository
public interface FilmRepository extends CrudRepository<Film, Long> {
    /**
     * find film by movie id
     * @param movieId movieId
     * @return Film
     */
    Film findFirstByMovieId(Long movieId);

    /**
     * get viewed or stared films by movie ids
     *
     * @param ids list of movieIds
     * @return list of Film
     */
    List<Film> findByMovieIdIsIn(List<Long> ids);

    /**
     * get all movies
     * @return list of Film
     */
    List<Film> findAllByOrderByMovieYearDescRatingDesc();

    /**
     * get movie list by movie type
     * @param movieTypeEnum movie type enum
     * @return list of Film
     */
    List<Film> findByMovieTypeEnumOrderByRatingDesc(MovieTypeEnum movieTypeEnum);
}
