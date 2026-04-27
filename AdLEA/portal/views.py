import requests
from .models import BlacklistedAd
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_POST
from django.contrib.auth.decorators import login_required
from django.contrib.auth import authenticate, login, logout
from django.contrib import messages
from django.shortcuts import render, redirect


def landing_page(request):
    return render(request, 'landing.html')


def register(request):

    if request.method == "POST":
        username = request.POST['username']
        email = request.POST['email']
        password = request.POST['password']

        user = User.objects.create_user(
            username=username,
            email=email,
            password=password
        )
        user.save()

        return redirect('login')

    return render(request, 'register.html')


def login_user(request):

    if request.method == "POST":
        username = request.POST['username']
        password = request.POST['password']

        user = authenticate(request, username=username, password=password)

        if user is not None:
            login(request, user)
            return redirect('dashboard')

    return render(request, 'login.html')


def dashboard(request):

    if not request.user.is_authenticated:
        return redirect('login')

    ads = BlacklistedAd.objects.filter(user=request.user)

    return render(request, "dashboard.html", {"ads": ads})


def logout_user(request):
    logout(request)
    return redirect('login')

@login_required
def search_ads(request):
    results = []
    error = None
    next_cursor = None
    keyword = ""

    # Restore previous search
    if request.method == "GET":
        results = request.session.get("search_results", [])
        keyword = request.session.get("search_keyword", "")
        next_cursor = request.session.get("next_cursor")

    if request.method == "POST":

        keyword = request.POST.get("keyword", "").strip()
        cursor = request.POST.get("cursor", "").strip()

        url = "https://api.scrapecreators.com/v1/facebook/adLibrary/search/ads"

        headers = {
            "x-api-key": settings.SCRAPECREATORS_API_KEY
        }

        params = {
            "query": keyword,
            "country": "IN"
        }

        if cursor:
            params["cursor"] = cursor

        try:

            response = requests.get(url, headers=headers, params=params, timeout=20)

            if response.status_code == 200:

                data = response.json()

                results = data.get("searchResults", [])
                next_cursor = data.get("next_cursor")

                # Save results in session
                request.session["search_results"] = results
                request.session["search_keyword"] = keyword
                request.session["next_cursor"] = next_cursor

        except Exception:
            error = "Error fetching ads"

    return render(
        request,
        "search.html",
        {
            "results": results,
            "error": error,
            "next_cursor": next_cursor,
            "keyword": keyword
        }
    )

@login_required
def report_ad(request):

    if request.method == "POST":

        title = request.POST.get("title")
        body = request.POST.get("body")
        link_url = request.POST.get("link_url")
        image_url = request.POST.get("image_url")

        BlacklistedAd.objects.create(
            user=request.user,
            title=title,
            body=body,
            link_url=link_url,
            image_url=image_url
        )

        return JsonResponse({"status": "success"})

    return JsonResponse({"status": "error"})