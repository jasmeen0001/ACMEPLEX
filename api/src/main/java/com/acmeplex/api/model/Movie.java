package com.acmeplex.api.model;

import jakarta.persistence.*;

import java.util.Set;

@Entity
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String description;

    private Integer durationInMinute;

    private String genre;

    private Boolean isOnlyAvailableForPrebooking;

    @Enumerated(EnumType.STRING)
    private MovieRating movieRating;

    private String imageUrl;
    @OneToMany(mappedBy = "movie")
    private Set<Showtime> showtimes;

    public Movie() {
    }

    public Movie(Long id, String title, String description,
                 Integer durationInMinute, String genre,
                 MovieRating movieRating, String imageUrl,
                 Boolean isOnlyAvailableForPrebooking) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.durationInMinute = durationInMinute;
        this.genre = genre;
        this.movieRating = movieRating;
        this.imageUrl = imageUrl;
        this.isOnlyAvailableForPrebooking = isOnlyAvailableForPrebooking;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getDurationInMinute() {
        return durationInMinute;
    }

    public void setDurationInMinute(Integer durationInMinute) {
        this.durationInMinute = durationInMinute;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public MovieRating getMovieRating() {
        return movieRating;
    }

    public void setMovieRating(MovieRating movieRating) {
        this.movieRating = movieRating;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Set<Showtime> getShowtimes() {
        return showtimes;
    }

    public void setShowtimes(Set<Showtime> showtimes) {
        this.showtimes = showtimes;
    }

    public Boolean getOnlyAvailableForPrebooking() {
        return isOnlyAvailableForPrebooking;
    }

    public void setOnlyAvailableForPrebooking(Boolean onlyAvailableForPrebooking) {
        isOnlyAvailableForPrebooking = onlyAvailableForPrebooking;
    }
}