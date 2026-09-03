package com.youkeda.exercise.claw.feature.weather;

/**
 * 天气响应数据模型
 * 封装从天气 API 获取的天气信息
 */
public class WeatherResponse {

    private String city;
    private String weather;
    private double temperature;
    private int humidity;
    private String date;
    private boolean forecast;
    private double minTemperature;
    private double maxTemperature;
    private int chanceOfRain;
    private double maxWindKph;

    public WeatherResponse() {
    }

    public WeatherResponse(String city, String weather, double temperature, int humidity) {
        this.city = city;
        this.weather = weather;
        this.temperature = temperature;
        this.humidity = humidity;
    }

    @Override
    public String toString() {
        return "城市：" + city + "\n" +
               "天气：" + weather + "\n" +
               "温度：" + Math.round(temperature) + "℃\n" +
               "湿度：" + humidity + "%";
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getWeather() {
        return weather;
    }

    public void setWeather(String weather) {
        this.weather = weather;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getHumidity() {
        return humidity;
    }

    public void setHumidity(int humidity) {
        this.humidity = humidity;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public boolean isForecast() { return forecast; }
    public void setForecast(boolean forecast) { this.forecast = forecast; }
    public double getMinTemperature() { return minTemperature; }
    public void setMinTemperature(double minTemperature) { this.minTemperature = minTemperature; }
    public double getMaxTemperature() { return maxTemperature; }
    public void setMaxTemperature(double maxTemperature) { this.maxTemperature = maxTemperature; }
    public int getChanceOfRain() { return chanceOfRain; }
    public void setChanceOfRain(int chanceOfRain) { this.chanceOfRain = chanceOfRain; }
    public double getMaxWindKph() { return maxWindKph; }
    public void setMaxWindKph(double maxWindKph) { this.maxWindKph = maxWindKph; }
}
